/***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2015 The JRuby Team
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.javasupport;

import org.jruby.IncludedModuleWrapper;
import org.jruby.MetaClass;
import org.jruby.Ruby;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyModule;
import org.jruby.RubyString;
import org.jruby.RubySymbol;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.internal.runtime.methods.NullMethod;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ObjectAllocator;
import org.jruby.runtime.Signature;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ClassProvider;

import static org.jruby.api.Check.checkID;
import static org.jruby.api.Convert.asBoolean;
import static org.jruby.api.Create.newString;
import static org.jruby.api.Error.argumentError;
import static org.jruby.api.Error.nameError;
import static org.jruby.runtime.Visibility.PRIVATE;

/**
 * A "thin" Java package wrapper (for the runtime to see them as Ruby objects).
 *
 * @since 9K
 * <p>Note: previously <code>JavaPackageModuleTemplate</code> in Ruby code</p>
 * @author kares
 */
@JRubyClass(name="Java::JavaPackage", parent="Module")
public class JavaPackage extends RubyModule {

    static RubyClass createJavaPackageClass(ThreadContext context, final RubyModule Java, RubyClass Module, RubyModule Kernel) {
        RubyClass superClass = new BlankSlateWrapper(context.runtime, Module, Kernel);
        RubyClass JavaPackage = RubyClass.newClass(context, superClass, null);
        JavaPackage.setMetaClass(Module);
        JavaPackage.allocator(ObjectAllocator.NOT_ALLOCATABLE_ALLOCATOR).
                baseName("JavaPackage").
                defineMethods(context, JavaPackage.class);
        ((MetaClass) JavaPackage.makeMetaClass(context, superClass)).setAttached(JavaPackage);
        JavaPackage.setParent(Java);
        return JavaPackage;
    }

    static RubyModule newPackage(Ruby runtime, CharSequence name, RubyModule parent) {
        final JavaPackage pkgModule = new JavaPackage(runtime, name);
        // intentionally do NOT set pkgModule.setParent(parent);

        // this is where we'll get connected when classes are opened using
        // package module syntax.
        pkgModule.addClassProvider( JavaClassProvider.INSTANCE );
        return pkgModule;
    }

    static CharSequence buildPackageName(final RubyModule parentPackage, final String name) {
        return ((JavaPackage) parentPackage).packageRelativeName(name);
    }

    final String packageName;

    private JavaPackage(final Ruby runtime, final CharSequence packageName) {
        super(runtime, runtime.getJavaSupport().getJavaPackageClass(), false); // java packages are phantom objects, and should never be added to objectspace
        this.packageName = packageName.toString();
    }

    public String getPackageName() {
        return packageName;
    }

    // NOTE: name is Ruby name not pkg.name ~ maybe it should be just like with JavaClass?

    @Deprecated(since = "10.0")
    public RubyString package_name() {
        return package_name(getCurrentContext());
    }

    @JRubyMethod(name = "package_name", alias = "to_s")
    public RubyString package_name(ThreadContext context) {
        return newString(context, packageName);
    }

    @Deprecated(since = "10.0")
    public RubyString to_s() {
        return (RubyString) to_s(getCurrentContext());
    }

    @Override
    public IRubyObject to_s(ThreadContext context) { return package_name(); }

    @JRubyMethod
    public IRubyObject inspect(ThreadContext context) {
        return newString(context, getName(context)); // super.to_s()
    }

    @Override
    @JRubyMethod(name = "===")
    public RubyBoolean op_eqq(ThreadContext context, IRubyObject obj) {
        // maybe we could handle java.lang === java.lang.reflect as well ?
        return asBoolean(context, obj == this || isInstance(obj));
    }

    @JRubyMethod(name = "const_missing")
    public IRubyObject const_missing(final ThreadContext context, final IRubyObject name) {
        return relativeJavaClassOrPackage(context, name, false);
    }

    @JRubyMethod(name = "const_get")
    public final IRubyObject const_get(final ThreadContext context, final IRubyObject name) {
        // skip constant validation and do not inherit or include object
        IRubyObject constant = getConstantNoConstMissing(context, name.toString(), false, false);
        return constant != null ? constant : relativeJavaClassOrPackage(context, name, false); // e.g. javax.const_get(:script)
    }

    @JRubyMethod(name = "const_get")
    public final IRubyObject const_get(final ThreadContext context, final IRubyObject name, final IRubyObject inherit) {
        IRubyObject constant = getConstantNoConstMissing(context, name.toString(), inherit.isTrue(), false);
        return constant != null ? constant : relativeJavaClassOrPackage(context, name, false);
    }

    @Override // skip constant name assert
    public final boolean hasConstant(String name) {
        return constantTableContains(name);
    }

    @Override // skip constant name assert
    public final IRubyObject fetchConstant(ThreadContext context, String name, boolean includePrivate) {
        ConstantEntry entry = constantEntryFetch(name);
        if (entry == null) return null;
        if (entry.hidden && !includePrivate) {
            throw nameError(context, "private constant " + getName(context) + "::" + name + " referenced", name);
        }
        return entry.value;
    }

    final CharSequence packageRelativeName(final CharSequence name) {
        final int length = packageName.length();
        final StringBuilder fullName = new StringBuilder(length + 1 + name.length());
        // packageName.length() > 0 ? package + '.' + name : name;
        fullName.append(packageName);
        if ( length > 0 ) fullName.append('.');
        return fullName.append(name);
    }

    private RubyModule relativeJavaClassOrPackage(final ThreadContext context,
        final IRubyObject name, final boolean cacheMethod) {
        return Java.getProxyOrPackageUnderPackage(context, this, name.toString(), cacheMethod);
    }

    @Deprecated(since = "10.0")
    RubyModule relativeJavaProxyClass(final Ruby runtime, final IRubyObject name) {
        var context = runtime.getCurrentContext();
        final String fullName = packageRelativeName( name.toString() ).toString();
        return Java.getProxyClass(context, Java.getJavaClass(context, fullName));
    }

    @JRubyMethod(name = "respond_to?")
    public IRubyObject respond_to_p(final ThreadContext context, IRubyObject name) {
        return respond_to(context, name, false);
    }

    @JRubyMethod(name = "respond_to?")
    public IRubyObject respond_to_p(final ThreadContext context, IRubyObject name, IRubyObject includePrivate) {
        return respond_to(context, name, includePrivate.isTrue());
    }

    private IRubyObject respond_to(final ThreadContext context, IRubyObject mname, final boolean includePrivate) {
        RubySymbol name = checkID(context, mname);

        if (getMetaClass().respondsToMethod(name.idString(), !includePrivate)) return context.tru;
        /*
        if ( ( name = BlankSlateWrapper.handlesMethod(name) ) != null ) {
            RubyBoolean bound = checkMetaClassBoundMethod(context, name, includePrivate);
            if ( bound != null ) return bound;
            return context.fals; // un-bound (removed) method
        }
        */

        //if ( ! (mname instanceof RubySymbol) ) mname = asSymbol(context, name);
        //IRubyObject respond = Helpers.invoke(context, this, "respond_to_missing?", mname, asBoolean(context, includePrivate));
        //return asBoolean(context, respond.isTrue());

        return context.nil; // NOTE: this is wrong - should be true but compatibility first, for now
    }

    private RubyBoolean checkMetaClassBoundMethod(final ThreadContext context, final String name, final boolean includePrivate) {
        // getMetaClass().isMethodBound(name, !includePrivate, true)
        DynamicMethod method = getMetaClass().searchMethod(name);
        if ( ! method.isUndefined() && ! method.isNotImplemented() ) {
            if ( ! includePrivate && method.getVisibility() == PRIVATE ) {
                return context.fals;
            }
            return context.tru;
        }
        return null;
    }

    @JRubyMethod(name = "respond_to_missing?")
    public IRubyObject respond_to_missing_p(final ThreadContext context, IRubyObject name) {
        return respond_to_missing(context, name, false);
    }

    @JRubyMethod(name = "respond_to_missing?")
    public IRubyObject respond_to_missing_p(final ThreadContext context, IRubyObject name, IRubyObject includePrivate) {
        return respond_to_missing(context, name, includePrivate.isTrue());
    }

    private RubyBoolean respond_to_missing(final ThreadContext context, IRubyObject mname, final boolean includePrivate) {
        return asBoolean(context, BlankSlateWrapper.handlesMethod(checkID(context, mname).idString()) == null);
    }

    @JRubyMethod(name = "method_missing")
    public IRubyObject method_missing(ThreadContext context, final IRubyObject name) {
        // NOTE: getProxyOrPackageUnderPackage binds the (cached) method for us
        return Java.getProxyOrPackageUnderPackage(context, this, name.toString(), true);
    }

    @JRubyMethod(name = "method_missing", rest = true)
    public IRubyObject method_missing(ThreadContext context, final IRubyObject[] args) {
        if (args.length > 1) throw packageMethodArgumentMismatch(context, this, args[0].toString(), args.length - 1);

        return method_missing(context, args[0]);
    }
    
    static RaiseException packageMethodArgumentMismatch(ThreadContext context, final RubyModule pkg,
        final String method, final int argsLength) {
        String packageName = ((JavaPackage) pkg).packageName;
        return argumentError(context,
                "Java package '" + packageName + "' does not have a method '" +
                        method + "' with " + argsLength + (argsLength == 1 ? " argument" : " arguments")
        );
    }

    public final boolean isAvailable() {
        // may be null if no package information is available from the archive or codebase
        return getPackage() != null;
    }

    @SuppressWarnings("deprecation")
    private Package getPackage() {
        // NOTE: can not switch to getRuntime().getJRubyClassLoader().getDefinedPackage(packageName) as it's Java 9+
        return Package.getPackage(packageName);
    }

    @JRubyMethod(name = "available?")
    public IRubyObject available_p(ThreadContext context) {
        return asBoolean(context, isAvailable());
    }

    @JRubyMethod(name = "sealed?")
    public IRubyObject sealed_p(ThreadContext context) {
        final Package pkg = getPackage();
        if ( pkg == null ) return context.nil;
        return asBoolean(context, pkg.isSealed());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T toJava(Class<T> target) {
        if ( target.isAssignableFrom( Package.class ) ) {
            return target.cast(getPackage());
        }
        return super.toJava(target);
    }

    @Override
    public int hashCode() {
        // avoid any dynamic calls to #hash
        return id;
    }

    private static class JavaClassProvider implements ClassProvider {

        static final JavaClassProvider INSTANCE = new JavaClassProvider();

        public RubyClass defineClassUnder(ThreadContext context, RubyModule pkg, String name, RubyClass superClazz) {
            // shouldn't happen, but if a superclass is specified, it's not ours
            if ( superClazz != null ) return null;

            final String subPackageName = JavaPackage.buildPackageName(pkg, name).toString();

            Class<?> javaClass = Java.getJavaClass(context, subPackageName);
            return (RubyClass) Java.getProxyClass(context, javaClass);
        }

        public RubyModule defineModuleUnder(ThreadContext context, RubyModule pkg, String name) {
            final String subPackageName = JavaPackage.buildPackageName(pkg, name).toString();

            Class<?> javaClass = Java.getJavaClass(context, subPackageName);
            return Java.getInterfaceModule(context, javaClass);
        }

    }

    /**
     * This special module wrapper is used by the Java "package modules" in order to
     * simulate a blank slate. Only a certain subset of method names will carry
     * through to searching the superclass, with all others returning null and
     * triggering the method_missing call needed to handle lazy Java package
     * discovery.
     *
     * Because this is in the hierarchy, it does mean any methods that are not Java
     * packages or otherwise defined on the <code>Java::JavaPackage</code> will
     * be inaccessible.
     */
    static final class BlankSlateWrapper extends IncludedModuleWrapper {

        BlankSlateWrapper(Ruby runtime, RubyClass superClass, RubyModule delegate) {
            super(runtime, superClass, delegate);
        }

        @Override
        protected DynamicMethod searchMethodCommon(String id) {
            // this module is special and only searches itself;
            if ("superclass".equals(id)) {
                return new MethodValue(id, superClass); // JavaPackage.superclass
            }
            return (id = handlesMethod(id)) != null ? superClass.searchMethodInner(id) : NullMethod.INSTANCE;
        }

        private static class MethodValue extends DynamicMethod {

            private final IRubyObject value;

            MethodValue(final String name, final IRubyObject value) {
                super(name);
                this.value = value;
            }

            public final IRubyObject call(ThreadContext context, IRubyObject self, RubyModule clazz, String name, IRubyObject[] args, Block block) {
                return call(context, self, clazz, name);
            }

            @Override
            public IRubyObject call(ThreadContext context, IRubyObject self, RubyModule klazz, String name) {
                return value;
            }

            @Override
            public DynamicMethod dup() {
                try {
                    return (DynamicMethod) super.clone();
                }
                catch (CloneNotSupportedException ex) {
                    throw new AssertionError(ex);
                }
            }

            @Deprecated @Override
            public Arity getArity() { return Arity.NO_ARGUMENTS; }

            public Signature getSignature() {
                return Signature.NO_ARGUMENTS;
            }
        }

        private static String handlesMethod(final String name) {
            // FIXME: We should consider pure-bytelist search here.
            switch (name) {
                case "class" : case "singleton_class" : return name;
                case "object_id" : case "name" : return name;
                // these are handled already at the JavaPackage.class :
                // case "const_get" : case "const_missing" : case "method_missing" :
                case "const_set" : return name;
                case "inspect" : case "to_s" : return name;
                // these are handled bellow in switch (name.charAt(0))
                // case "__method__" : case "__send__" : case "__id__" :

                //case "require" : case "load" :
                case "throw" : case "catch" : //case "fail" : case "raise" :
                //case "exit" : case "at_exit" :
                    return name;

                case "singleton_method_added" :
                // JavaPackageModuleTemplate handled "singleton_method_added"
                case "singleton_method_undefined" :
                case "singleton_method_removed" :
                case "define_singleton_method" :
                    return name;

                // NOTE: these should maybe get re-thought and deprecated (for now due compatibility)
                case "__constants__" : return "constants";
                case "__methods__" : return "methods";
            }

            final int last = name.length() - 1;
            if ( last >= 0 ) {
                switch (name.charAt(last)) {
                    case '?' : case '!' : case '=' :
                        return name;
                }
                switch (name.charAt(0)) {
                    case '<' : case '>' : case '=' : // e.g. ==
                        return name;
                    case '_' : // e.g. __send__
                        if ( last > 0 && name.charAt(1) == '_' ) {
                            return name;
                        }
                }
            }

            //if ( last >= 5 && (
            //       name.indexOf("method") >= 0 || // method, instance_methods, singleton_methods ...
            //       name.indexOf("variable") >= 0 || // class_variables, class_variable_get, instance_variables ...
            //       name.indexOf("constant") >= 0 ) ) { // constants, :public_constant, :private_constant
            //    return true;
            //}

            return null;
        }

        @Override
        public void addSubclass(RubyClass subclass) { /* noop */ }

    }

}
