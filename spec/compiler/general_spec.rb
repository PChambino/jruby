require 'jruby'
require 'java'
require 'rspec'

module CompilerSpecUtils
  def silence_warnings
    verb = $VERBOSE
    $VERBOSE = nil
    yield
  ensure
    $VERBOSE = verb
  end
end

module InterpreterSpecUtils
  include CompilerSpecUtils

  def run_in_method(src, filename = caller_locations[0].path, line = caller_locations[0].lineno)
    run( "def __temp; #{src}; end; __temp", filename, line)
  end

  def run(src, filename = caller_locations[0].path, line = caller_locations[0].lineno)
    yield eval(src, TOPLEVEL_BINDING, filename, line)
  end

  def self.name; "interpreter"; end
end

module PersistenceSpecUtils
  include CompilerSpecUtils

  def initialize(*x, **y)
    super
    @persist_runtime = org.jruby.Ruby.newInstance
  end

  attr_reader :persist_runtime

  def run_in_method(src, filename = caller_locations[0].path, line = caller_locations[0].lineno)
    run( "def __temp; #{src}; end; __temp", filename, line)
  end

  def run(src, filename = caller_locations[0].path, line = caller_locations[0].lineno)
    yield encode_decode_run(src, filename, line)
  end

  def self.name; "persistence"; end

  private

  def encode_decode_run(src, filename, line)
    # persist with separate runtime
    jruby_module = persist_runtime.eval_scriptlet("require 'jruby'; JRuby")
    persist_context = persist_runtime.current_context
    persist_src = persist_runtime.new_string(src)
    persist_filename = persist_runtime.new_string(src)
    persist_line = persist_runtime.new_fixnum(line - 1)
    method = org.jruby.ext.jruby.JRubyLibrary.compile_ir(
      persist_context,
      jruby_module,
      [persist_src,
       persist_filename,
       persist_runtime.false,
       persist_line].to_java(org.jruby.runtime.builtin.IRubyObject),
      org.jruby.runtime.Block::NULL_BLOCK)

    # encode and decode
    baos = java.io.ByteArrayOutputStream.new
    writer = org.jruby.ir.persistence.IRWriterStream.new(baos)
    org.jruby.ir.persistence.IRWriter.persist(writer, method)

    # interpret with test runtime
    runtime = JRuby.runtime
    context = runtime.get_current_context
    manager = runtime.getIRManager()
    top_self = runtime.top_self

    reader = org.jruby.ir.persistence.IRReaderStream.new(manager, baos.to_byte_array, filename.to_java)
    method = org.jruby.ir.persistence.IRReader.load(manager, reader)

    interpreter = org.jruby.ir.interpreter.Interpreter.new
    interpreter.execute(context, method, top_self)
  end
end

module JITSpecUtils
  include CompilerSpecUtils

  def run_in_method(src, filename = caller_locations[0].path, line = caller_locations[0].lineno)
    run( "def __temp; #{src}; end; __temp", filename, line)
  end

  def run(src, filename = caller_locations[0].path, line = caller_locations[0].lineno)
    yield compile_run(src, filename, line)
  end

  def self.name; "jit"; end

  private

  def compile_to_method(src, filename, lineno)
    node = JRuby.parse(src, filename, false, lineno)
    runtime = JRuby.runtime

    # This logic is a mix of logic from InterpretedIRMethod's JIT, o.j.Ruby's script compilation, and IRScriptBody's
    # interpret. We need to figure out a cleaner path.

    scope = node.getStaticScope
    currModule = scope.getModule
    if currModule == nil
      scope.setModule currModule = runtime.top_self.class
    end

    method = org.jruby.ir.builder.IRBuilder.build_root(runtime.getIRManager(), node).scope
    method.prepareForCompilation

    compiler = new_visitor(runtime)
    compiled = compiler.compile(method, org.jruby.util.OneShotClassLoader.new(runtime.getJRubyClassLoader()))
    scriptMethod = compiled.getMethod(
        org.jruby.util.JavaNameMangler::SCRIPT_METHOD_NAME,
        org.jruby.runtime.ThreadContext.java_class,
        org.jruby.parser.StaticScope.java_class,
        org.jruby.runtime.builtin.IRubyObject.java_class,
        org.jruby.runtime.builtin.IRubyObject[].java_class,
        org.jruby.runtime.Block.java_class,
        org.jruby.RubyModule.java_class,
        java.lang.String.java_class)
    handle = java.lang.invoke.MethodHandles.publicLookup().unreflect(scriptMethod)
    if method.kind_of?(org.jruby.ir.IRMethod) || method.kind_of?(org.jruby.ir.IRClosure)
      descriptors = org.jruby.runtime.ArgumentDescriptor.encode(method.argument_descriptors)
    else
      descriptors = ""
    end

    return org.jruby.internal.runtime.methods.CompiledIRMethod.new(
        handle,
        method,
        org.jruby.runtime.Visibility::PUBLIC,
        currModule,
        descriptors)
  end

  def new_visitor(runtime)
    org.jruby.ir.targets.JVMVisitor.newForJIT(runtime)
  end

  def compile_run(src, filename, line)
    cls = compile_to_method(src, filename, line - 1) # compiler expects zero-based lines

    cls.call(
        JRuby.runtime.current_context,
        JRuby.runtime.top_self,
        JRuby.runtime.top_self.class,
        "script",
        IRubyObject[0].new,
        Block::NULL_BLOCK)
  end
end

module AOTSpecUtils
  include JITSpecUtils

  def new_visitor(runtime)
    org.jruby.ir.targets.JVMVisitor.newForAOT(runtime)
  end

  def self.name; "aot"; end
end

modes = []
modes << InterpreterSpecUtils unless (ENV['INTERPRETER_TEST'] == 'false')
modes << PersistenceSpecUtils unless (ENV['PERSISTENCE_TEST'] == 'false')
modes << JITSpecUtils unless (ENV['COMPILER_TEST'] == 'false')
modes << AOTSpecUtils unless (ENV['AOT_TEST'] == 'false')

Block = org.jruby.runtime.Block
IRubyObject = org.jruby.runtime.builtin.IRubyObject

modes.each do |mode|
  describe "JRuby's #{mode.name}" do
    include mode

    it "assigns literal values to locals" do
      run("a = 5; a") {|result| expect(result).to eq 5 }
      run("a = 5.5; a") {|result| expect(result).to eq 5.5 }
      run("a = 'hello'; a") {|result| expect(result).to eq 'hello' }
      run("a = :hello; a") {|result| expect(result).to eq :hello }
      run("a = 1111111111111111111111111111; a") {|result| expect(result).to eq 1111111111111111111111111111 }
      run("a = [1, ['foo', :hello]]; a") {|result| expect(result).to eq([1, ['foo', :hello]]) }
      run("{}") {|result| expect(result).to eq({}) }
      run("a = {:foo => {:bar => 5.5}}; a") {|result| expect(result).to eq({:foo => {:bar => 5.5}}) }
      run("a = /foo/; a") {|result| expect(result).to eq(/foo/) }
      run("1..2") {|result| expect(result).to eq (1..2) }
      run("1...2") {|result| expect(result).to eq (1...2) }
      run("1r") {|result| expect(result).to eq (Rational(1, 1))}
      run("1.1r") {|result| expect(result).to eq (Rational(11, 10))}
      run("1i") {|result| expect(result).to eq (Complex(0, 1))}
      run("1.1i") {|result| expect(result).to eq (Complex(0, 1.1))}
    end

    it "compiles interpolated strings" do
      run('a = "hello#{42}"; a') {|result| expect(result).to eq('hello42') }
      run('i = 1; a = "hello#{i + 42}"; a') {|result| expect(result).to eq("hello43") }
      # same cases in presence of refinements
      run('class NoToS; end; module AddToS; refine(NoToS){def to_s; "42"; end}; end; class TryToS; using AddToS; def self.a; "hello#{NoToS.new}"; end; end; TryToS.a') {|result| expect(result).to eq('hello42') }

      # https://github.com/jruby/jruby/issues/8847
      pid_dstr_32_times = '#{$$}' * 32
      pid_32_times = ([$$] * 32).join('')
      run("\"hello#{pid_dstr_32_times}\"") {|result| expect(result).to eq('hello' + pid_32_times) }
    end

    it "compiles calls" do
      run("'bar'.capitalize") {|result| expect(result).to eq 'Bar' }
      run("rand(10)") {|result| expect(result).to be_a_kind_of Integer }
    end

    it "compiles branches" do
      run("a = 1; if 1 == a; 2; else; 3; end") {|result| expect(result).to eq 2 }
      run("a = 1; unless 1 == a; 2; else; 3; end") {|result| expect(result).to eq 3 }
      run("a = 1; while a < 10; a += 1; end; a") {|result| expect(result).to eq 10 }
      run("a = 1; until a == 10; a += 1; end; a") {|result| expect(result).to eq 10 }
      run("2 if true") {|result| expect(result).to eq 2 }
      run("2 if false") {|result| expect(result).to be_nil }
      run("2 unless true") {|result| expect(result).to be_nil }
      run("2 unless false") {|result| expect(result).to eq 2 }
    end

    it "compiles while loops with no body" do
      run("@foo = true; def flip; @foo = !@foo; end; while flip; end") do |result|
        expect(result).to eq nil
      end
    end

    it "compiles boolean operators" do
      run("1 && 2") {|result| expect(result).to eq 2 }
      run("nil && 2") {|result| expect(result).to be_nil }
      run("nil && fail") {|result| expect(result).to be_nil }
      run("1 || 2") {|result| expect(result).to eq 1 }
      run("nil || 2") {|result| expect(result).to eq 2 }
      expect {run(nil || fail){}}.to raise_error(RuntimeError)
      run("1 and 2") {|result| expect(result).to eq 2 }
      run("1 or 2") {|result| expect(result).to eq 1 }
    end

    it "compiles begin blocks" do
      run("begin; a = 4; end; a") {|result| expect(result).to eq 4 }
    end

    it "compiles regexp matches" do
      run("/foo/ =~ 'foo'") {|result| expect(result).to eq 0 }
      run("'foo' =~ /foo/") {|result| expect(result).to eq 0 }
      run(":aaa =~ /foo/") {|result| expect(result).to be_nil }
    end

    it "compiles method definitions" do
      run("def foo3(arg); arg + '2'; end; foo3('baz')") {|result| expect(result).to eq 'baz2' }
      run("def self.foo3(arg); arg + '2'; end; self.foo3('baz')") {|result| expect(result).to eq 'baz2' }
    end

    it "compiles calls with closures" do
      run("def foo2(a); a + yield.to_s; end; foo2('baz') { 4 }") {|result| expect(result).to eq 'baz4' }
      run("def foo2(a); a + yield.to_s; end; foo2('baz') {}") {|result| expect(result).to eq 'baz' }
      run("def self.foo2(a); a + yield.to_s; end; self.foo2('baz') { 4 }") {|result| expect(result).to eq 'baz4' }
      run("def self.foo2(a); a + yield.to_s; end; self.foo2('baz') {}") {|result| expect(result).to eq 'baz' }
    end

    it "compiles strings with encoding" do
      str8bit = '"\300"'
      run(str8bit) do |str8bit_result|
        expect(str8bit_result).to eq "\300"
        expect(str8bit_result.encoding).to eq Encoding::UTF_8
      end
    end

    it "compiles backrefs" do
      base = "'0123456789A' =~ /(1)(2)(3)(4)(5)(6)(7)(8)(9)/; "
      run(base + "$~") {|result| expect(result).to be_a_kind_of MatchData }
      run(base + "$`") {|result| expect(result).to eq '0' }
      run(base + "$'") {|result| expect(result).to eq 'A' }
      run(base + "$+") {|result| expect(result).to eq '9' }
      run(base + "$0") {|result| expect(result).to eq $0 } # main script name, not related to matching
      run(base + "$1") {|result| expect(result).to eq '1' }
      run(base + "$2") {|result| expect(result).to eq '2' }
      run(base + "$3") {|result| expect(result).to eq '3' }
      run(base + "$4") {|result| expect(result).to eq '4' }
      run(base + "$5") {|result| expect(result).to eq '5' }
      run(base + "$6") {|result| expect(result).to eq '6' }
      run(base + "$7") {|result| expect(result).to eq '7' }
      run(base + "$8") {|result| expect(result).to eq '8' }
      run(base + "$9") {|result| expect(result).to eq '9' }
    end

    it "compiles aliases" do
      run("alias :to_string1 :to_s; defined?(self.to_string1)") {|result| expect(result).to eq "method" }
      run("alias to_string2 to_s; defined?(self.to_string2)") {|result| expect(result).to eq "method" }
    end

    it "compiles block-local variables" do
      blocks_code = <<-EOS
        def a
          yield 3
        end

        arr = []
        x = 1
        1.times {
          y = 2
          arr << x
          x = 3
          a {
            arr << y
            y = 4
            arr << x
            x = 5
          }
          arr << y
          arr << x
          x = 6
        }
        arr << x
        arr
        EOS
      run(blocks_code) {|result| expect(result).to eq([1,2,3,4,5,6]) }
    end

    it "compiles yield" do
      run("def foo; yield 1; end; foo {|a| a + 2}") {|result| expect(result).to eq 3 }

      yield_in_block = <<-EOS
        def foo
          bar { yield }
        end
        def bar
          yield
        end
        foo { 1 }
        EOS
      run(yield_in_block) {|result| expect(result).to eq 1}

      yield_in_proc = <<-EOS
        def foo
          proc { yield }
        end
        p = foo { 1 }
        p.call
        EOS
      run(yield_in_proc) {|result| expect(result).to eq 1 }
    end

    it "compiles attribute assignment" do
      run("public; def a=(x); 2; end; self.a = 1") {|result| expect(result).to eq 1 }
      run("public; def a; 1; end; def a=(arg); fail; end; self.a ||= 2") {|result| expect(result).to eq 1 }
      run("public; def a; @a; end; def a=(arg); @a = arg; 4; end; x = self.a ||= 1; [x, self.a]") {|result| expect(result).to eq([1,1]) }
      run("public; def a; nil; end; def a=(arg); fail; end; self.a &&= 2") {|result| expect(result).to be_nil }
      run("public; def a; @a; end; def a=(arg); @a = arg; end; @a = 3; x = self.a &&= 1; [x, self.a]") {|result| expect(result).to eq([1,1]) }
    end

    it "compiles lastline" do
      run("def foo; $_ = 1; bar; $_; end; def bar; $_ = 2; end; foo") {|result| expect(result).to eq 1 }
    end

    it "compiles closure arguments" do
      run("a = 0; [1].each {|a|}; a") {|result| expect(result).to eq(0) }
      run("a = 0; [1].each {|x| a = x}; a") {|result| expect(result).to eq 1 }
      run("[[1,2,3]].each {|x,*y| break y}") {|result| expect(result).to eq([2,3]) }
      run("1.times {|x,*y| break y}") {|result| expect(result).to eq([]) }
      run("1.times {|x,*|; break x}") {|result| expect(result).to eq 0 }
    end

    it "compiles class definitions" do
      class_string = <<-EOS
        class CompiledClass1
          def foo
            "cc1"
          end
        end
        CompiledClass1.new.foo
        EOS
      run(class_string) {|result| expect(result).to eq 'cc1' }
    end

    it "compiles module definitions" do
      module_string = <<-EOS
        module CompiledModule1
          def self.bar
            "cm1"
          end
        end
        CompiledModule1.bar
      EOS

      run(module_string) {|result| expect(result).to eq 'cm1' }
    end

    it "compiles operator assignment" do
      run("class H; attr_accessor :v; end; H.new.v ||= 1") {|result| expect(result).to eq 1 }
      run("class H; def initialize; @v = true; end; attr_accessor :v; end; H.new.v &&= 2") {|result| expect(result).to eq 2 }
      run("class H; def initialize; @v = 1; end; attr_accessor :v; end; H.new.v += 3") {|result| expect(result).to eq 4 }
    end

    it "compiles optional method arguments" do
      run("def foo(a,b=1);[a,b];end;foo(1)") {|result| expect(result).to eq([1,1]) }
      run("def foo(a,b=1);[a,b];end;foo(1,2)") {|result| expect(result).to eq([1,2]) }
      expect{run("def foo(a,b=1);[a,b];end;foo")}.to raise_error(ArgumentError)
      expect{run("def foo(a,b=1);[a,b];end;foo(1,2,3)")}.to raise_error(ArgumentError)
      run("def foo(a=(b=1));[a,b];end;foo") {|result| expect(result).to eq([1,1]) }
      run("def foo(a=(b=1));[a,b];end;foo(2)") {|result| expect(result).to eq([2,nil]) }
      run("def foo(a, b=(c=1));[a,b,c];end;foo(1)") {|result| expect(result).to eq([1,1,1]) }
      run("def foo(a, b=(c=1));[a,b,c];end;foo(1,2)") {|result| expect(result).to eq([1,2,nil]) }
      expect{run("def foo(a, b=(c=1));[a,b,c];end;foo(1,2,3)")}.to raise_error(ArgumentError)
    end

    it "compiles accesses of uninitialized variables" do
      run("def foo(a); if a; b = 1; end; b.inspect; end; foo(false)") {|result| expect(result).to eq("nil") }
      run("def foo(a); a ||= (b = 1); b.inspect; end; foo(1)") {|result| expect(result).to eq("nil")}
      run("def foo(a); a &&= (b = 1); b.inspect; end; foo(nil)") {|result| expect(result).to eq("nil")}
    end

    it "compiles grouped and intra-list rest args" do
      run("def foo(a, (b, *, c), d, *e, f, (g, *h, i), j); [a,b,c,d,e,f,g,h,i,j]; end; foo(1,[2,3,4],5,6,7,8,[9,10,11],12)") do |result|
        expect(result).to eq([1, 2, 4, 5, [6, 7], 8, 9, [10], 11, 12])
      end
    end

    it "compiles splatted values" do
      run("def foo(a,b,c);[a,b,c];end;foo(1, *[2, 3])") {|result| expect(result).to eq([1,2,3]) }
    end

    it "compiles multiple assignment" do
      run("a = nil; 1.times { a, b, @c = 1, 2, 3; a = [a, b, @c] }; a") {|result| expect(result).to eq([1,2,3]) }
      run("a, (b, c) = 1; [a, b, c]") {|result| expect(result).to eq([1,nil,nil]) }
      run("a, (b, c) = 1, 2; [a, b, c]") {|result| expect(result).to eq([1,2,nil]) }
      run("a, (b, c) = 1, [2, 3]; [a, b, c]") {|result| expect(result).to eq([1,2,3]) }
      run("class Coercible2;def to_ary;[2,3]; end; end; a, (b, c) = 1, Coercible2.new; [a, b, c]") {|result| expect(result).to eq([1,2,3]) }
      run("a, (b, *, c), d, *e, f, (g, *h, i), j = 1,[2,3,4],5,6,7,8,[9,10,11],12; [a,b,c,d,e,f,g,h,i,j]") do |result|
        expect(result).to eq([1, 2, 4, 5, [6, 7], 8, 9, [10], 11, 12])
      end
    end

    it "compiles dynamic regexp" do
      # test different arities since we optimize smaller ones
      1.upto(10) do |i|
        run('x = "foo"; i = ' + i.to_s + '; x * i =~ /' + '#{x}' * i + '/') {|result| expect(result).to eq 0 }
      end

      run('ary = []; 2.times {|i| ary << ("foo0" =~ /#{"foo" + i.to_s}/o)}; ary') {|result| expect(result).to eq([0, 0]) }
    end

    it "compiles implicit and explicit return" do
      run("def foo; 1; end; foo") {|result| expect(result).to eq 1 }
      run("def foo; return; end; foo") {|result| expect(result).to be_nil }
      run("def foo; return 1; end; foo") {|result| expect(result).to eq 1 }
    end

    it "compiles class reopening" do
      run("class Integer; def x; 3; end; end; 1.x") {|result| expect(result).to eq 3 }
    end

    it "compiles singleton method definitions" do
      run("a = +'bar'; def a.foo; 'foo'; end; a.foo") {|result| expect(result).to eq "foo" }
      run("class Integer; def self.foo; 'foo'; end; end; Integer.foo") {|result| expect(result).to eq "foo" }
      run("def String.foo; 'foo'; end; String.foo") {|result| expect(result).to eq "foo" }
    end

    it "compiles singleton class definitions" do
      run("a = +'bar'; class << a; def bar; 'bar'; end; end; a.bar") {|result| expect(result).to eq "bar" }
      run("class Integer; class << self; def bar; 'bar'; end; end; end; Integer.bar") {|result| expect(result).to eq "bar" }
      run("class Integer; def self.metaclass; class << self; self; end; end; end; Integer.metaclass") do |result|
        expect(result).to eq class << Integer; self; end
      end
    end

    it "compiles loops with flow control" do
      # some loop flow control tests
      run("a = true; b = while a; a = false; break; end; b") {|result| expect(result).to be_nil }
      run("a = true; b = while a; a = false; break 1; end; b") {|result| expect(result).to eq 1 }
      run("a = 0; while true; a += 1; next if a < 2; break; end; a") {|result| expect(result).to eq 2 }
      run("a = 0; while true; a += 1; next 1 if a < 2; break; end; a") {|result| expect(result).to eq 2 }
      run("a = 0; while true; a += 1; redo if a < 2; break; end; a") {|result| expect(result).to eq 2 }
      run("a = false; b = until a; a = true; break; end; b") {|result| expect(result).to be_nil }
      run("a = false; b = until a; a = true; break 1; end; b") {|result| expect(result).to eq 1 }
      run("a = 0; until false; a += 1; next if a < 2; break; end; a") {|result| expect(result).to eq 2 }
      run("a = 0; until false; a += 1; next 1 if a < 2; break; end; a") {|result| expect(result).to eq 2 }
      run("a = 0; until false; a += 1; redo if a < 2; break; end; a") {|result| expect(result).to eq 2 }
    end

    it "compiles loops with non-local flow control" do
      # non-local flow control with while loops
      run("a = 0; 1.times { a += 1; redo if a < 2 }; a") {|result| expect(result).to eq 2 }
      run("def foo(&b); while true; b.call; end; end; foo { break 3 }") {|result| expect(result).to eq 3 }
      
      expect(lambda { run("def foo(&b); while true; b.call; end; end; foo { eval 'break 3' }") }).to raise_error(SyntaxError)
    end

    it "compiles block passing" do
      # block pass node compilation
      run("def foo; block_given?; end; p = proc {}; [foo(&nil),foo(&p)]") {|result| expect(result).to eq([false, true]) }
      run("public; def foo; block_given?; end; p = proc {}; [self.foo(&nil),self.foo(&p)]") {|result| expect(result).to eq([false, true]) }
    end

    it "compiles splatted element assignment" do
      run("a = +'foo'; y = ['o']; a[*y] = 'asdf'; a") {|result| expect(result).to match "fasdfo" }
    end

    it "compiles constant access" do
      const_code = <<-EOS
        A ||= 'a'; module X; B ||= 'b'; end; module Y; def self.go; [A, X::B, ::A]; end; end; Y.go
      EOS
      run(const_code) {|result| expect(result).to eq(["a", "b", "a"]) }
    end

    # it "compiles flip-flop" do
    #   # flip (taken from http://redhanded.hobix.com/inspect/hopscotchingArraysWithFlipFlops.html)
    #   run_in_method("s = true; (1..10).reject { true if (s = !s) .. (s) }") {|result| expect(result).to eq([1, 3, 5, 7, 9]) }
    #   run_in_method("s = true; (1..10).reject { true if (s = !s) .. (s = !s) }") {|result| expect(result).to eq([1, 4, 7, 10]) }
    #   big_flip = <<-EOS
    #   s = true; (1..10).inject([]) do |ary, v|; ary << [] unless (s = !s) .. (s = !s); ary.last << v; ary; end
    #   EOS
    #   run_in_method(big_flip) {|result| expect(result).to eq([[1, 2, 3], [4, 5, 6], [7, 8, 9], [10]]) }
    #   big_triple_flip = <<-EOS
    #   s = true
    #   (1..64).inject([]) do |ary, v|
    #       unless (s ^= v[2].zero?)...(s ^= !v[1].zero?)
    #           ary << []
    #       end
    #       ary.last << v
    #       ary
    #   end
    #   EOS
    #   expected = [[1, 2, 3, 4, 5, 6, 7, 8],
    #               [9, 10, 11, 12, 13, 14, 15, 16],
    #               [17, 18, 19, 20, 21, 22, 23, 24],
    #               [25, 26, 27, 28, 29, 30, 31, 32],
    #               [33, 34, 35, 36, 37, 38, 39, 40],
    #               [41, 42, 43, 44, 45, 46, 47, 48],
    #               [49, 50, 51, 52, 53, 54, 55, 56],
    #               [57, 58, 59, 60, 61, 62, 63, 64]]
    #   run_in_method(big_triple_flip) {|result| expect(result).to eq(expected) }
    # end

    it "gracefully handles named captures when there's no match" do
      expect do
        run('/(?<a>.+)/ =~ ""') {}
      end.to_not raise_error
    end

    it "handles module/class opening from colon2 with non-method, non-const LHS" do
      expect do
        run('m = Object; class m::FOOCLASS1234; end; module m::FOOMOD1234; end') {}
      end.to_not raise_error
    end

    it "properly handles non-local flow for a loop inside an ensure (JRUBY-6836)" do
      ary = []
      run('
        def main
          ary = []
          while true
            begin
              break
            ensure
              ary << 1
            end
          end
          ary << 2
        ensure
          ary << 3
        end

        main') do |result|
        expect(result).to eq([1,2,3])
      end
    end

    it "prepares a proper caller scope for partition/rpartition (JRUBY-6827)" do
      run(%q[
        def foo
          Object
          "/Users/headius/projects/jruby/tmp/perfer/examples/file_stat.rb:4:in `(root)'".rpartition(/:\d+(?:$|:in )/).first
        end

        foo]) do |result|
        expect(result).to eq '/Users/headius/projects/jruby/tmp/perfer/examples/file_stat.rb'
      end
    end

    it "handles attr accessors for unassigned vars properly" do
      # under invokedynamic, we were caching the "dummy" accessor that never saw any value
      run('
  class AttrAccessorUnassigned
    attr_accessor :foo
  end

  obj = AttrAccessorUnassigned.new
  ary = []
  2.times { ary << obj.foo; obj.foo = 1}
  ary
      ') do |result|
        expect(result).to eq([nil, 1])
      end
    end


    it "does not break String#to_r and to_c" do
      # This is structured to cause a "dummy" scope because of the String constant
      # This caused to_r and to_c to fail since that scope always returns nil
      run('
      def foo
        [String.new("0.1".to_c.to_s), String.new("0.1".to_r.to_s)]
      end
      foo
      ') do |result|
        expect(result).to eq(["0.1+0i", "1/10"])
      end
    end

    it "handles optimized homogeneous case/when" do
      run('
        ["a", "b", "c", "d", "e", "f", "g", "h", "i", "j"].map do |x|
          case x
          when "a"
            1
          when "b"
            2
          when "c"
            3
          when "d"
            4
          when "e"
            5
          when "f"
            6
          when "g"
            7
          when "h"
            8
          when "i"
            9
          when "j"
            10
          else
            fail
          end
        end
      ') do |result|
        expect(result).to eq [1,2,3,4,5,6,7,8,9,10]
      end

      run('
        [:zxcvbnmzxcvbnm, :qwertyuiopqwertyuiop, :asdfghjklasdfghjkl, :a, :z].map do |x|
          case x
          when :zxcvbnmzxcvbnm
            1
          when :qwertyuiopqwertyuiop
            2
          when :asdfghjklasdfghjkl
            3
          when :a
            4
          when :z
            5
          else
            fail
          end
          end
      ') do |result|
        expect(result).to eq [1,2,3,4,5]
      end

      run('
        [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map do |x|
          case x
          when 1
            1
          when 2
            2
          when 3
            3
          when 4
            4
          when 5
            5
          when 6
            6
          when 7
            7
          when 8
            8
          when 9
            9
          when 10
            10
          else
            fail
          end
        end
      ') do |result|
        expect(result).to eq [1,2,3,4,5,6,7,8,9,10]
      end
    end     

    it "handles 0-4 arg and splatted whens in a caseless case/when" do
      run('
        case
        when false
          fail
        when false, false
          fail
        when false, false, false
          fail
        when false, false, false, false
          fail
        when *[false, false, false, false]
        else
          42
        end
      ') do |result|
        expect(result).to eq 42
      end
    end

    it "matches any true value for a caseless case/when with > 3 args" do
      result = run('
        case
        when false, false, false, true
          42
        end
      ') do |result|
        expect(result).to eq 42
      end
    end

    it "properly handles method-root rescue logic with returns (GH\#733)" do
      run("def foo; return 1; rescue; return 2; else; return 3; end; foo") {|result| expect(result).to eq 1 }
      run("def foo; 1; rescue; return 2; else; return 3; end; foo") {|result| expect(result).to eq 3 }
      run("def foo; raise; rescue; return 2; else; return 3; end; foo") {|result| expect(result).to eq 2 }
    end

    it "mangles filenames internally to avoid conflicting delimiters when building descriptors (GH\#961)" do
      run(
        "1.times { 1 }",
        "my,0.25,file:with:many|odd|delimiters.rb"
      ) do |result|
        expect(result).to eq 1
      end
    end

    it "keeps backref local to the caller scope when calling !~" do
      run('
        Class.new do
          def blank?
            "a" !~ /[^[:space:]]/
          end
        end.new
      ') do |obj|
        $~ = nil
        expect(obj).not_to be_blank
        expect($~).to be_nil
      end
    end

    # GH-1239
    it "properly scopes singleton method definitions in a compiled body" do
      run("
        class GH1239
          def self.define; def gh1239; end; end
          def self.remove; remove_method :gh1239; end
        end
        GH1239
      ") do |cls|

        cls.define
        expect(cls.methods).not_to be_include :gh1239
        expect{cls.remove}.not_to raise_error
      end
    end

    it "yields nil when yielding no arguments" do
      silence_warnings {
        # bug 1305, no values yielded to single-arg block assigns a null into the arg
        run("def foo; yield; end; foo {|x| x.class}") {|result| expect(result).to eq NilClass }
      }
    end

    it "prevents reopening or extending non-modules" do
      # ensure that invalid classes and modules raise errors
      AInteger ||= 1
      expect { run("class AInteger; end")}.to raise_error(TypeError)
      expect { run("class B < AInteger; end")}.to raise_error(TypeError)
      expect { run("module AInteger; end")}.to raise_error(TypeError)
    end

    it "assigns array elements properly as LHS of masgn" do
      # attr assignment in multiple assign
      run("a = Object.new; class << a; attr_accessor :b; end; a.b, a.b = 'baz','bar'; a.b") {|result| expect(result).to eq "bar" }
      run("a = []; a[0], a[1] = 'foo','bar'; a") {|result| expect(result).to eq(["foo", "bar"]) }
    end

    it "executes for loops properly" do
      # for loops
      run("a = []; for b in [1, 2, 3]; a << b * 2; end; a") {|result| expect(result).to eq([2, 4, 6]) }
      run("a = []; for b, c in {:a => 1, :b => 2, :c => 3}; a << c; end; a.sort") {|result| expect(result).to eq([1, 2, 3]) }
    end

    it "fires ensure blocks after normal or early block termination" do
      # ensure blocks
      run("a = 2; begin; a = 3; ensure; a = 1; end; a") {|result| expect(result).to eq 1 }
      run("$a = 2; def foo; return; ensure; $a = 1; end; foo; $a") {|result| expect(result).to eq 1 }
    end

    it "handles array element assignment with ||, +, and && operators" do
      # op element assign
      run("a = []; [a[0] ||= 4, a[0]]") {|result| expect(result).to eq([4, 4]) }
      run("a = [4]; [a[0] ||= 5, a[0]]") {|result| expect(result).to eq([4, 4]) }
      run("a = [1]; [a[0] += 3, a[0]]") {|result| expect(result).to eq([4, 4]) }
      run("a = {}; a[0] ||= [1]; a[0]") {|result| expect(result).to eq([1]) }
      run("a = [1]; a[0] &&= 2; a[0]") {|result| expect(result).to eq 2 }
    end

    it "propagates closure returns to the method body" do
      # non-local return
      run("def foo; loop {return 3}; return 4; end; foo") {|result| expect(result).to eq 3 }
    end

    it "handles class variable declaration and access" do
      # class var declaration
      run("class Foo; @@foo = 3; end") {|result| expect(result).to eq 3 }
      run("class Bar; @@bar = 3; def self.bar; @@bar; end; end; Bar.bar") {|result| expect(result).to eq 3 }
    end

    it "handles exceptional flow transfer to rescue blocks" do
      # rescue
      run("x = begin; 1; raise; rescue; 2; end") {|result| expect(result).to eq 2 }
      run("x = begin; 1; raise; rescue TypeError; 2; rescue; 3; end") {|result| expect(result).to eq 3 }
      run("x = begin; 1; rescue; 2; else; 4; end") {|result| expect(result).to eq 4 }
      run("def foo; begin; return 4; rescue; end; return 3; end; foo") {|result| expect(result).to eq 4 }
    end

    it "properly resets $! to nil upon normal exit from a rescue" do
      # test that $! is getting reset/cleared appropriately
      run("begin; raise; rescue; end; $!") {|result| expect(result).to be_nil }
      run("1.times { begin; raise; rescue; next; end }; $!") {|result| expect(result).to be_nil }
      run("begin; raise; rescue; begin; raise; rescue; end; $!; end") {|result| expect(result).to_not be_nil }
      run("begin; raise; rescue; 1.times { begin; raise; rescue; next; end }; $!; end") {|result| expect(result).to_not be_nil }
    end

    it "executes ensure wrapping a while body that breaks after the loop has terminated" do
      # break in a while in an ensure
      run("begin; x = while true; break 5; end; ensure; end") {|result| expect(result).to eq 5 }
    end

    it "resolves Foo::Bar style constants" do
      # JRUBY-1388, Foo::Bar broke in the compiler
      silence_warnings do
        run("module Foo2; end; Foo2::Foo3 = 5; Foo2::Foo3") {|result| expect(result).to eq 5 }
      end
    end

    it "re-runs enclosing block when redo is called from ensure" do
      run("def foo; yield; end; x = false; foo { break 5 if x; begin; ensure; x = true; redo; end; break 6}") {|result| expect(result).to eq 5 }
    end

    it "compiles END Blocks" do
      # END block
      expect { run("END {}"){} }.to_not raise_error
    end

    it "compiles BEGIN blocks" do
      # BEGIN block
      run("BEGIN { $begin = 5 }; $begin") {|result| expect(result).to eq 5 }
    end

    it "compiles empty source" do
      # nothing at all!
      run("") {|result| expect(result).to be_nil }
    end

    it "properly assigns values in masgn without overwriting neighboring values" do
      # JRUBY-2043
      run("def foo; 1.times { a, b = [], 5; a[1] = []; return b; }; end; foo") {|result| expect(result).to eq 5 }
      run("def foo; x = {1 => 2}; x.inject({}) do |hash, (key, value)|; hash[key.to_s] = value; hash; end; end; foo") {|result| expect(result).to eq({"1" => 2}) }
    end

    it "compiles very long code bodies" do
      skip "JRUBY-2246"
      long_src = "a = 1\n"
      5000.times { long_src << "a += 1\n" }
      run(long_src) {|result| expect(result).to eq 5001 }
    end

    it "assigns the result of a terminated loop to LHS variable" do
      # variable assignment of various types from loop results
      run("class Loupe; def loupe; a = while true; break 1; end; a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @a = while true; break 1; end; @a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @@a = while true; break 1; end; @@a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; $a = while true; break 1; end; $a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; a = until false; break 1; end; a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @a = until false; break 1; end; @a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @@a = until false; break 1; end; @@a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; $a = until false; break 1; end; $a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }

      # same assignments but loop is within a begin
      run("class Loupe; def loupe; a = begin; while true; break 1; end; end; a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @a = begin; while true; break 1; end; end; @a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @@a = begin; while true; break 1; end; end; @@a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; $a = begin; while true; break 1; end; end; $a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; a = begin; until false; break 1; end; end; a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @a = begin; until false; break 1; end; end; @a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; @@a = begin; until false; break 1; end; end; @@a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }
      run("class Loupe; def loupe; $a = begin; until false; break 1; end; end; $a; end; end; Loupe.new.loupe") {|result| expect(result).to eq 1 }

      # other contexts that require while to preserve stack
      run("1 + while true; break 1; end") {|result| expect(result).to eq 2 }
      run("1 + begin; while true; break 1; end; end") {|result| expect(result).to eq 2 }
      run("1 + until false; break 1; end") {|result| expect(result).to eq 2 }
      run("1 + begin; until false; break 1; end; end") {|result| expect(result).to eq 2 }
      run("def foo(a); a; end; foo(while false; end)") {|result| expect(result).to be_nil }
      run("def foo(a); a; end; foo(until true; end)") {|result| expect(result).to be_nil }
    end

    it "constructs symbols on first execution and retrieves them from cache on subsequent executions" do
      # test that 100 symbols compiles ok; that hits both types of symbol caching/creation
      syms = [:a]
      99.times {|i| syms << ('foo' + i.to_s).intern }
      # 100 first instances of a symbol
      run(syms.inspect) {|result| expect(result).to eq syms }
      # 100 first instances and 100 second instances (caching)
      run("[#{syms.inspect},#{syms.inspect}]") {|result| expect(result).to eq([syms,syms]) }
    end

    it "can extend a class contained in a local variable" do
      # class created using local var as superclass
      run(<<-EOS) {|result| expect(result).to eq 'AFromLocal' }
      a = Object
      class AFromLocal < a
      end
      AFromLocal.to_s
      EOS
    end

    it "can compile large literal arrays and hashes" do
      skip "JRUBY-4757 and JRUBY-2621: can't compile large array/hash"

      large_array = (1..10000).to_a.inspect
      large_hash = large_array.clone
      large_hash.gsub!('[', '{')
      large_hash.gsub!(']', '}')
      run(large_array) do |result|
        expect(result).to eq(eval(large_array) {|result| expect(result) })
      end
    end

    it "properly spreads incoming array when block args contain multiple variables" do
      # block arg spreading cases
      run("def foo; a = [1]; yield a; end; foo {|a| a}") {|result| expect(result).to eq([1]) }
      run("x = nil; [[1]].each {|a| x = a}; x") {|result| expect(result).to eq([1]) }
      run("def foo; yield [1, 2]; end; foo {|x, y| [x, y]}") {|result| expect(result).to eq([1,2]) }
    end

    it "compiles non-expression case statements without an else clause" do
      # non-expr case statement with return with if modified with call
      # broke in 1.9 compiler due to null "else" node pushing a nil when non-expr
      run("def foo; case 0; when 1; return 2 if self.nil?; end; return 3; end; foo") {|result| expect(result).to eq 3 }
    end

    it "assigns named groups in regular expressions to local variables" do
      # named groups with capture
      run("
      def foo
        ary = []
        a = nil
        b = nil
        1.times {
          /(?<b>ell)(?<c>o)/ =~ 'hello'
          ary << a
          ary << b
          ary << c
        }
        ary << b
        ary
      end
      foo") do |result|
        expect(result).to eq([nil,'ell', 'o', 'ell'])
      end
    end

    it "handles complicated splatting at beginning and end of literal array" do
      # chained argscat and argspush
      run("a=[1,2];b=[4,5];[*a,3,*a,*b]") {|result| expect(result).to eq([1,2,3,1,2,4,5]) }
    end

    it "dispatches super and zsuper arguments correctly in the presence of a rest argument" do
      # JRUBY-5871: test that "special" args dispatch along specific-arity path
      test = '
      %w[foo bar].__send__ :to_enum, *[], &nil
      '
      run(test) do |result|
        expect(result.map {|line| line + 'yum'}).to eq(["fooyum", "baryum"])
      end

      # These two cases triggered ArgumentError when Enumerator was fixed to enforce
      # 3 required along its varargs path. Testing both here to ensure super/zsuper
      # also dispatch along arity-specific paths as appropriate
      enumerable = "Enumerator"
      expect{run("
      class JRuby5871A < #{enumerable}
        def initialize(x, y, *z)
          super
        end
      end
      "){}}.to_not raise_error

      # Enumerator.new without a block
      expect {
        JRuby5871A.new("foo", :each_byte)
      }.to raise_error(ArgumentError)

      expect{run("
      class JRuby5871B < #{enumerable}
        def initialize(x, y, *z)
          super(x, y, *z)
        end
      end
      "){}}.to_not raise_error

      # Enumerator.new without a block
      expect {
        JRuby5871B.new("foo", :each_byte)
      }.to raise_error(ArgumentError)
    end

    it "allows colon2 const assignment on LHS of masgn" do
      class JRUBY4925
      end

      silence_warnings do
        run 'JRUBY4925::BLAH, a = 1, 2' do |x|
          expect(JRUBY4925::BLAH).to eq 1
        end
        run '::JRUBY4925_BLAH, a = 1, 2' do |x|
          expect(JRUBY4925_BLAH).to eq 1
        end
      end
    end

    it "compiles backquotes (backtick)" do
      run 'o = Object.new; def o.`(str); str; end; def o.go; `hello`; end; o.go' do |x|
        expect(x).to eq 'hello'
      end
    end

    it "creates frozen strings for backquotes (backtick)" do
      run 'o = Object.new; def o.`(str); str; end; def o.go; `hello`; end; o.go' do |x|
        expect(x).to be_frozen
      end
    end

    it "compiles rest args passed to return, break, and next (svalue)" do
      run 'a = [1,2,3]; 1.times { break *a }' do |x|
        expect(x).to eq [1,2,3]
      end

      run 'a = [1,2,3]; lambda { return *a }.call' do |x|
        expect(x).to eq [1,2,3]
      end

      run 'a = [1,2,3]; def foo; yield; end; foo { next *a }' do |x|
        expect(x).to eq [1,2,3]
      end
    end

    it "compiles optional arguments in a method with toplevel rescue" do
      run 'def foo(a = false); raise; rescue; a; end; foo' do |x|
        expect(x).to eq false
      end
    end

    it "compiles optional arguments with a constant" do
      run 'def foo(a = Object); a; end; foo' do |x|
        expect(x).to eq Object
      end
    end

    it "retrieves toplevel constants with ::Const form" do
      run '::Object' do |x|
        expect(x).to eq Object
      end
    end

    it "splats arguments to super" do
      run '
        class SplatSuperArgs0
          def foo(a, b, c)
            a + b + c
          end
        end
        class SplatSuperArgs1 < SplatSuperArgs0
          def foo(*args)
            super(*args)
          end
        end
        SplatSuperArgs1.new.foo(1, 2, 3)' do |x|
        expect(x).to eq 6
      end
    end

    it "performs super calls within a closure" do
      run '
        class SplatSuperArgs0
          def foo(a)
            a
          end
        end
        class SplatSuperArgs1 < SplatSuperArgs0
          def foo(a)
            1.times do
              super(a)
            end
          end
        end
        SplatSuperArgs1.new.foo(1)' do |x|
        expect(x).to eq 1
      end
    end

    it "passes kwargs through zsuper correctly" do
      run 'class X1; def foo(a:1, b:2); [a, b]; end; end; class X2 < X1; def foo(a:1, b:2); a = 5; super; end; end; X2.new.foo(a:3, b:4)' do |x|
        expect(x).to eq [5,4]
      end
    end

    it "raises errors for missing required keyword arguments" do
      expect {run('def foo(a:); end; foo'){}}.to raise_error(ArgumentError)
    end

    it "passes keyrest arguments through zsuper correctly" do
      run '
        class C
          def foo(str: "foo", num: 42, **opts)
          [str, num, opts]
          end
        end

        class D < C
          def foo(str: "bar", num: 45, **opts)
          super
          end
        end

        [C.new.foo, D.new.foo, D.new.foo(str: "d", num:75, a:1, b:2)]
      ' do |x|

        expect(x).to eq [
            ["foo", 42, {}],
            ["bar", 45, {}],
            ["d",   75, {a:1,b:2}]
                        ]
      end
    end

    it "handles dynamic case/when elements" do
      # These use a global so IR does not inline it from a local var
      run('$case_str = "z"; case "xyz"; when /#{$case_str}/; true; else; false; end') do |x|
        expect(x).to eq(true)
      end
      run('$case_str = "xyz"; case "xyz"; when "#{$case_str}"; true; else; false; end') do |x|
        expect(x).to eq(true)
      end
      run('$case_str = "xyz"; case :xyz; when :"#{$case_str}"; true; else; false; end') do |x|
        expect(x).to eq(true)
      end
    end

    it "handles lists of conditions in case/when" do
      run('$case_str = "z"; case "xyz"; when /#{$$}/, /#{$case_str}/; true; else; false; end') do |x|
        expect(x).to eq(true)
      end
    end

    it "handles literal arrays in case/when" do
      run('$case_ary = [1,2,3]; case $case_ary; when [1,2,3]; true; else; false; end') do |x|
        expect(x).to eq(true)
      end
    end

    it "enforces visibility" do
      run('obj = Class.new do
             def a; true; end
             private def b; true; end
             protected; def c; true; end
           end.new
           [obj.a, (obj.b rescue false), (obj.c rescue false)]') do |x|
        expect(x).to eq([true, false, false])
      end
    end

    it "pushes call name into frame" do
      run('obj = Class.new do
             def a; __callee__; end
             define_method :b, instance_method(:a)
           end.new
           [obj.a, obj.b]') do |x|
        expect(x).to eq([:a, :b])
      end
    end

    it "raises appropriate missing-method error for call type" do
      # Variable
      run('begin; does_not_exist; rescue NameError; $!; end') do |x|
        expect(x).to be_instance_of(NameError)
      end

      # Functional
      run('begin; does_not_exist(); rescue NameError; $!; end') do |x|
        expect(x).to be_instance_of(NoMethodError)
      end

      # Normal
      run('begin; self.does_not_exist; rescue NameError; $!; end') do |x|
        expect(x).to be_instance_of(NoMethodError)
      end
    end

    it "preserves 'encoding none' flag for literal regexp" do
      run('/a/n.options') do |x|
        expect(x).to eq(32)
      end
    end

    it "handles nested [], loops, and break" do
      run('def foo
             while 1
               "x"[0]
               while 1
                 raise "ok"
                 break
               end
             end
           end
           foo rescue $!') do |x|
        expect(x.message).to eq("ok")
      end
    end

    it "returns a proper __FILE__ and __LINE__" do
      run('[__FILE__, __LINE__]', 'foobar.rb', 1) do |x, y|
        expect(x).to eq('foobar.rb')
        expect(y).to eq(1)
      end
    end

    it "combines optional args and zsuper properly" do
      begin
        verbose = $VERBOSE
        $VERBOSE = nil

        run('class OptZSuperA; def foo(a, b); [a, b]; end; end; class OptZSuperB < OptZSuperA; def foo(a = "", b = nil); super; end; end; OptZSuperB.new.foo') do |x|
          expect(x).to eq(["", nil])
        end

        run('class OptZSuperA; def foo(a:, b:); [a, b]; end; end; class OptZSuperB < OptZSuperA; def foo(a: "", b: nil); super; end; end; OptZSuperB.new.foo') do |x|
          expect(x).to eq(["", nil])
        end
      ensure
        $VERBOSE = verbose
      end
    end

    it "maintains frame stack integrity through a bare lambda (GH #3643)" do
      code = '
        module GH3643
          class A
            def x(proc)
              instance_eval(&proc) rescue nil
              :ok
            end
          end
          A.prepend(Module.new { def x(proc); super; super; end })
          def self.foo
            A.new.x(lambda{})
          end
          foo
        end
      '

      run(code) do |x|
        expect(x).to eq(:ok)
      end
    end

    it "compiles calls with one Integer arg that do not have optimized paths" do
      run('ary = []; ary.push(1)') {|x| expect(x).to eq([1]) }
    end

    it "compiles calls with one float arg that do not have optimized paths" do
      run('ary = []; ary.push(1.0)') {|x| expect(x).to eq([1.0]) }
    end

    # jruby/jruby#4148
    it "binds variable-arity calls to attributes properly" do
      run('a_class = Class.new do; attr_accessor :foo; end; a = a_class.new; a.foo = 1; ary = []; a.foo(*ary)') do |x|
        expect(x).to eq(1)
      end
    end

    it "handles defined? super forms" do
      run('a = Class.new { def a; end }; b = Class.new(a) { def a; [defined? super, defined? super()]; end }.new; b.a') do |x|
        expect(x).to eq(["super", "super"])
      end

      # inside a block
      run('a = Class.new { def a; end }; b = Class.new(a) { def a; proc { [defined? super, defined? super()] }.call; end }.new; b.a') do |x|
        expect(x).to eq(["super", "super"])
      end
    end

    it "handles defined? method forms" do
      run('a = Class.new { def a; [defined? a, defined? a()]; end }.new; [a.a, defined? a.a]') do |x|
        expect(x).to eq([["method", "method"], "method"])
      end
    end

    it "handles defined? a.b= forms" do
      run('a = Class.new { attr_writer :b; def []=(_,_); end; def a; [defined? self.b=0, defined? self[0]=0]; end }.new; [a.a, defined? a.b = 0, defined? a[0] = 0]') do |x|
        expect(x).to eq([["method", "method"], "method", "method"])
      end
    end

    it "handles defined? Foo::xxx forms" do
      run('DefinedConstant ||= 1; o = Object.new; def o.foo; end; [defined? Object::DefinedConstant, defined? o::foo]') do |x|
        expect(x).to eq(["constant", "method"])
      end
    end

    it "handles defined? $~ forms" do
      run('/(foo)/ =~ "barfoobaz"; [defined? $~, defined? $1, defined? $`, defined? $\', defined? $&, defined? $+]') do |x|
        expect(x).to eq(%w[global-variable] * 6)
      end
    end

    it "handles defined? $global" do
      run('defined? $"') do |x|
        expect(x).to eq("global-variable")
      end
    end

    it "handles refined AsString constructs" do
      run('
          class ThingToRefine; end
          module RefineForAsString; refine(ThingToRefine) { def to_s; "foo"; end }; end
          class AsStringWithUsing; using RefineForAsString; def go; /#{ThingToRefine.new}/; end; end
          AsStringWithUsing.new.go') do |x|
        expect(x).to eq(/foo/)
      end
    end

    it "handles method_missing dispatch forms" do
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; obj.foo()') {|x| expect(x).to eq([:foo])}
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; obj.foo(1)') {|x| expect(x).to eq([:foo, 1])}
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; obj.foo(1,2)') {|x| expect(x).to eq([:foo, 1, 2])}
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; obj.foo(1,2,3)') {|x| expect(x).to eq([:foo, 1, 2, 3])}
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; obj.foo(1,2,3,4)') {|x| expect(x).to eq([:foo, 1, 2, 3, 4])}
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; ary = 1.upto(4).to_a; obj.foo(*ary)') {|x| expect(x).to eq([:foo, 1, 2, 3, 4])}
      run('obj = Class.new { def method_missing(name, *args); [name, *args]; end }.new; ary = 2.upto(4).to_a; obj.foo(1, *ary)') {|x| expect(x).to eq([:foo, 1, 2, 3, 4])}
    end

    it "handles send dispatch forms" do
      run('obj = Class.new { def foo(*args); args; end }.new; obj.send(:foo,1)') {|x| expect(x).to eq([1])}
      run('obj = Class.new { def foo(*args); args; end }.new; obj.send(:foo,1,2,3,4)') {|x| expect(x).to eq([1, 2, 3, 4])}
      run('obj = Class.new { def foo(*args); args; end }.new; ary = 1.upto(4).to_a; obj.send(:foo,*ary)') {|x| expect(x).to eq([1, 2, 3, 4])}
      run('obj = Class.new { def foo(*args); args; end }.new; ary = 2.upto(4).to_a; obj.send(:foo,1,*ary)') {|x| expect(x).to eq([1, 2, 3, 4])}
    end

    it "calls compiled methods of all arities with and without block" do
      run('Class.new { def foo; 1; end; }.new.foo') {|x| expect(x).to eq(1)}
      run('Class.new { def foo(a); a; end; }.new.foo(1)') {|x| expect(x).to eq(1)}
      run('Class.new { def foo(a, b); [a, b]; end; }.new.foo(1,2)') {|x| expect(x).to eq([1,2])}
      run('Class.new { def foo(a, b, c); [a, b, c]; end; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { def foo(a, b, c, d); [a, b, c, d]; end; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { def foo(*a); a; end; }.new.foo()') {|x| expect(x).to eq([])}
      run('Class.new { def foo(*a); a; end; }.new.foo(1)') {|x| expect(x).to eq([1])}
      run('Class.new { def foo(*a); a; end; }.new.foo(1,2)') {|x| expect(x).to eq([1,2])}
      run('Class.new { def foo(*a); a; end; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { def foo(*a); a; end; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { def foo(a, *b); [a, b]; end; }.new.foo(1)') {|x| expect(x).to eq([1,[]])}
      run('Class.new { def foo(a, *b); [a, b]; end; }.new.foo(1,2)') {|x| expect(x).to eq([1,[2]])}
      run('Class.new { def foo(a, *b); [a, b]; end; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,[2,3]])}
      run('Class.new { def foo(a, *b); [a, b]; end; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,[2,3,4]])}
      run('Class.new { def foo(a, b, *c); [a, b, c]; end; }.new.foo(1,2)') {|x| expect(x).to eq([1,2,[]])}
      run('Class.new { def foo(a, b, *c); [a, b, c]; end; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,[3]])}
      run('Class.new { def foo(a, b, *c); [a, b, c]; end; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,[3,4]])}
      run('Class.new { def foo(a, b, c, *d); [a, b, c, d]; end; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,3,[]])}
      run('Class.new { def foo(a, b, c, *d); [a, b, c, d]; end; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,3,[4]])}

      run('Class.new { def foo; yield 1; end; }.new.foo{|x|x}') {|x| expect(x).to eq(1)}
      run('Class.new { def foo(a); yield a; end; }.new.foo(1){|x|x}') {|x| expect(x).to eq(1)}
      run('Class.new { def foo(a, b); yield [a, b]; end; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,2])}
      run('Class.new { def foo(a, b, c); yield [a, b, c]; end; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { def foo(a, b, c, d); yield [a, b, c, d]; end; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { def foo(*a); yield a; end; }.new.foo(){|x|x}') {|x| expect(x).to eq([])}
      run('Class.new { def foo(*a); yield a; end; }.new.foo(1){|x|x}') {|x| expect(x).to eq([1])}
      run('Class.new { def foo(*a); yield a; end; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,2])}
      run('Class.new { def foo(*a); yield a; end; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { def foo(*a); yield a; end; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { def foo(a, *b); yield [a, b]; end; }.new.foo(1){|x|x}') {|x| expect(x).to eq([1,[]])}
      run('Class.new { def foo(a, *b); yield [a, b]; end; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,[2]])}
      run('Class.new { def foo(a, *b); yield [a, b]; end; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,[2,3]])}
      run('Class.new { def foo(a, *b); yield [a, b]; end; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,[2,3,4]])}
      run('Class.new { def foo(a, b, *c); yield [a, b, c]; end; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,2,[]])}
      run('Class.new { def foo(a, b, *c); yield [a, b, c]; end; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,[3]])}
      run('Class.new { def foo(a, b, *c); yield [a, b, c]; end; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,[3,4]])}
      run('Class.new { def foo(a, b, c, *d); yield [a, b, c, d]; end; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,3,[]])}
      run('Class.new { def foo(a, b, c, *d); yield [a, b, c, d]; end; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,3,[4]])}
    end

    it "calls interpreted methods of all arities with and without block" do
      run('Class.new { eval "def foo; 1; end"; }.new.foo') {|x| expect(x).to eq(1)}
      run('Class.new { eval "def foo(a); a; end"; }.new.foo(1)') {|x| expect(x).to eq(1)}
      run('Class.new { eval "def foo(a, b); [a, b]; end"; }.new.foo(1,2)') {|x| expect(x).to eq([1,2])}
      run('Class.new { eval "def foo(a, b, c); [a, b, c]; end"; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { eval "def foo(a, b, c, d); [a, b, c, d]; end"; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { eval "def foo(*a); a; end"; }.new.foo()') {|x| expect(x).to eq([])}
      run('Class.new { eval "def foo(*a); a; end"; }.new.foo(1)') {|x| expect(x).to eq([1])}
      run('Class.new { eval "def foo(*a); a; end"; }.new.foo(1,2)') {|x| expect(x).to eq([1,2])}
      run('Class.new { eval "def foo(*a); a; end"; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { eval "def foo(*a); a; end"; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { eval "def foo(a, *b); [a, b]; end"; }.new.foo(1)') {|x| expect(x).to eq([1,[]])}
      run('Class.new { eval "def foo(a, *b); [a, b]; end"; }.new.foo(1,2)') {|x| expect(x).to eq([1,[2]])}
      run('Class.new { eval "def foo(a, *b); [a, b]; end"; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,[2,3]])}
      run('Class.new { eval "def foo(a, *b); [a, b]; end"; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,[2,3,4]])}
      run('Class.new { eval "def foo(a, b, *c); [a, b, c]; end"; }.new.foo(1,2)') {|x| expect(x).to eq([1,2,[]])}
      run('Class.new { eval "def foo(a, b, *c); [a, b, c]; end"; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,[3]])}
      run('Class.new { eval "def foo(a, b, *c); [a, b, c]; end"; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,[3,4]])}
      run('Class.new { eval "def foo(a, b, c, *d); [a, b, c, d]; end"; }.new.foo(1,2,3)') {|x| expect(x).to eq([1,2,3,[]])}
      run('Class.new { eval "def foo(a, b, c, *d); [a, b, c, d]; end"; }.new.foo(1,2,3,4)') {|x| expect(x).to eq([1,2,3,[4]])}

      run('Class.new { eval "def foo; yield 1; end"; }.new.foo{|x|x}') {|x| expect(x).to eq(1)}
      run('Class.new { eval "def foo(a); yield a; end"; }.new.foo(1){|x|x}') {|x| expect(x).to eq(1)}
      run('Class.new { eval "def foo(a, b); yield [a, b]; end"; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,2])}
      run('Class.new { eval "def foo(a, b, c); yield [a, b, c]; end"; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { eval "def foo(a, b, c, d); yield [a, b, c, d]; end"; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { eval "def foo(*a); yield a; end"; }.new.foo(){|x|x}') {|x| expect(x).to eq([])}
      run('Class.new { eval "def foo(*a); yield a; end"; }.new.foo(1){|x|x}') {|x| expect(x).to eq([1])}
      run('Class.new { eval "def foo(*a); yield a; end"; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,2])}
      run('Class.new { eval "def foo(*a); yield a; end"; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,3])}
      run('Class.new { eval "def foo(*a); yield a; end"; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,3,4])}
      run('Class.new { eval "def foo(a, *b); yield [a, b]; end"; }.new.foo(1){|x|x}') {|x| expect(x).to eq([1,[]])}
      run('Class.new { eval "def foo(a, *b); yield [a, b]; end"; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,[2]])}
      run('Class.new { eval "def foo(a, *b); yield [a, b]; end"; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,[2,3]])}
      run('Class.new { eval "def foo(a, *b); yield [a, b]; end"; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,[2,3,4]])}
      run('Class.new { eval "def foo(a, b, *c); yield [a, b, c]; end"; }.new.foo(1,2){|x|x}') {|x| expect(x).to eq([1,2,[]])}
      run('Class.new { eval "def foo(a, b, *c); yield [a, b, c]; end"; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,[3]])}
      run('Class.new { eval "def foo(a, b, *c); yield [a, b, c]; end"; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,[3,4]])}
      run('Class.new { eval "def foo(a, b, c, *d); yield [a, b, c, d]; end"; }.new.foo(1,2,3){|x|x}') {|x| expect(x).to eq([1,2,3,[]])}
      run('Class.new { eval "def foo(a, b, c, *d); yield [a, b, c, d]; end"; }.new.foo(1,2,3,4){|x|x}') {|x| expect(x).to eq([1,2,3,[4]])}
    end

    it "calls root package methods with and without block" do
      run('java') {|x| expect(x).to eq(java)}
      run('javax') {|x| expect(x).to eq(javax)}
      run('javafx') {|x| expect(x).to eq(javafx)}
      run('org') {|x| expect(x).to eq(org)}
      run('com') {|x| expect(x).to eq(com)}
    end

    # See jruby/jruby#7246, test must loop twice to hit optimized dispatch
    it "dispatches to Java using a block to implement an interface" do
      run('ary = []; 2.times { java.util.ArrayList.new([1]).forEach { |e| ary << e } }; ary') {|ary| ary.should == [1, 1]}
    end

    it "calls struct field methods" do
      run('StructTest1 = Struct.new(:foo); st1 = StructTest1.new; st1.foo = 1; st1.foo') {|x| expect(x).to eq(1)}
      run('StructTest2 = Struct.new(:foo); class StructTest2; def do_foo; self.foo = 1; foo; end; end; StructTest2.new.do_foo') {|x| expect(x).to eq(1)}
    end

    it "calls aref with string key" do
      # optimized case for hash receiver
      run('def foo; {"a" => 1}; end; ary = foo; ary["a"] = 5; ary["a"]') {|val| expect(val).to eq(5)}
      # normal case for non-hash
      run('def foo; "abcd"; end; str = foo; str["a"]') {|val| expect(val).to eq("a")}
      # method_missing case
      run('o = Object.new; def o.method_missing(sym, a); :ok; end; o["a"]') {|val| expect(val).to eq(:ok)}
      # failed call site case, calls twice to trigger monomorphic cache in fail path
      run('10.times.map {o = Object.new; def o.[](a); 1; end; o}.map{|o| 2.times {o["a"]}; o["a"]}.sum') {|val| expect(val).to eq(10)}
      # failed call site with method_missing
      run(<<-AREF) {|val| expect(val).to eq(10)}
        ary = 10.times.map do |i|
          o = Object.new
          if i < 9
            def o.[](a); 1; end
          else
            def o.method_missing(sym, a); 1; end;
          end
          o
        end
        ary.map{|o| o["a"]}.sum
      AREF
      # compare_by_identity after usage
      run('h = {"a" => 1}; val = nil; 2.times { val = h["a"]; h.compare_by_identity }; val') {|val| expect(val).to eq(nil)}
    end

    it "handles instance super calls with a block" do
      run(<<-SUPER) {|val| expect(val).to eq(1)}
        class AInstanceSuper
          def instance_super
            yield
          end
        end
        class BInstanceSuper < AInstanceSuper
          def instance_super
            super {1}
          end
        end
        BInstanceSuper.new.instance_super
      SUPER
    end

    it "handles module super calls with a block" do
      run(<<-SUPER) {|val| expect(val).to eq(1)}
          class AModuleSuper
            def module_super
              yield
            end
          end
          module BModuleSuper
            def module_super
              super {1}
            end
          end
          class CModuleSuper < AModuleSuper
            include BModuleSuper
          end
          CModuleSuper.new.module_super
      SUPER
    end

    it "handles class super calls with a block" do
      run(<<-SUPER) {|val| expect(val).to eq(1)}
        class AInstanceSuper
          def self.class_super
            yield
          end
        end
        class BInstanceSuper < AInstanceSuper
          def self.class_super
            super {1}
          end
        end
        BInstanceSuper.class_super
      SUPER
    end

    it "handles zsuper calls" do
      run(<<-SUPER) {|val| expect(val).to eq(1)}
        class AZSuper
          def z_super
            1
          end
        end
        class BZSuper < AZSuper
          def z_super
            1.times { super }
          end
        end
        BZSuper.new.z_super
      SUPER
    end

    it "compiles debug chilled strings that can be modified without impacting other strings" do
      JRuby.runtime.instance_config.debugging_frozen_string_literal = true
      run(<<~CHILLED) {|val| expect(val).to eq("")}
        str = ""
        str << "hello"
        ""
      CHILLED
    ensure
      JRuby.runtime.instance_config.debugging_frozen_string_literal = false
    end
  end
end
