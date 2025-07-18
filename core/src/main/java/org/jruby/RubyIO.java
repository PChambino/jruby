/*
 **** BEGIN LICENSE BLOCK *****
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
 * Copyright (C) 2001-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2006 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004-2006 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2006 Evan Buswell <ebuswell@gmail.com>
 * Copyright (C) 2007 Miguel Covarrubias <mlcovarrubias@gmail.com>
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

package org.jruby;

import java.io.Closeable;
import java.io.Flushable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import jnr.constants.platform.Errno;
import jnr.constants.platform.Fcntl;
import jnr.constants.platform.OpenFlags;
import jnr.constants.platform.PosixFadvise;
import jnr.enxio.channels.NativeDeviceChannel;
import jnr.enxio.channels.NativeSelectableChannel;
import jnr.posix.Linux;
import jnr.posix.POSIX;

import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.transcode.EConvFlags;
import org.jruby.api.API;
import org.jruby.ast.util.ArgsUtil;
import org.jruby.anno.JRubyMethod;
import org.jruby.anno.JRubyClass;
import org.jruby.exceptions.EOFError;
import org.jruby.exceptions.IOError;
import org.jruby.exceptions.RaiseException;
import org.jruby.ext.fcntl.FcntlLibrary;
import org.jruby.internal.runtime.ThreadedRunnable;
import org.jruby.ir.runtime.IRRuntimeHelpers;
import org.jruby.platform.Platform;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.JavaSites.IOSites;
import org.jruby.runtime.Signature;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.Visibility;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.callsite.CachingCallSite;
import org.jruby.runtime.encoding.EncodingService;
import org.jruby.util.ShellLauncher.POpenProcess;
import org.jruby.util.*;
import org.jruby.util.cli.Options;
import org.jruby.util.io.ChannelFD;
import org.jruby.util.io.EncodingUtils;
import org.jruby.util.io.FilenoUtil;
import org.jruby.util.io.Getline;
import org.jruby.util.io.IOEncodable;
import org.jruby.util.io.IOOptions;
import org.jruby.util.io.InvalidValueException;
import org.jruby.util.io.ModeFlags;
import org.jruby.util.io.OpenFile;
import org.jruby.util.io.POSIXProcess;
import org.jruby.util.io.PopenExecutor;
import org.jruby.util.io.PosixShim;
import org.jruby.util.io.SelectExecutor;
import org.jruby.util.io.STDIO;

import static com.headius.backport9.buffer.Buffers.clearBuffer;
import static com.headius.backport9.buffer.Buffers.flipBuffer;
import static com.headius.backport9.buffer.Buffers.limitBuffer;
import static org.jruby.RubyEnumerator.enumeratorize;
import static org.jruby.RubyInteger.toInteger;
import static org.jruby.anno.FrameField.LASTLINE;
import static org.jruby.api.Access.argsFile;
import static org.jruby.api.Access.encodingService;
import static org.jruby.api.Access.fileClass;
import static org.jruby.api.Access.floatClass;
import static org.jruby.api.Access.globalVariables;
import static org.jruby.api.Access.ioClass;
import static org.jruby.api.Convert.asBoolean;
import static org.jruby.api.Convert.asFixnum;
import static org.jruby.api.Convert.asSymbol;
import static org.jruby.api.Convert.checkToInteger;
import static org.jruby.api.Convert.toByte;
import static org.jruby.api.Convert.toInt;
import static org.jruby.api.Convert.toLong;
import static org.jruby.api.Create.newArray;
import static org.jruby.api.Create.newHash;
import static org.jruby.api.Create.newSharedString;
import static org.jruby.api.Create.newString;
import static org.jruby.api.Define.defineClass;
import static org.jruby.api.Error.argumentError;
import static org.jruby.api.Error.runtimeError;
import static org.jruby.api.Error.typeError;
import static org.jruby.api.Warn.warn;
import static org.jruby.api.Warn.warnDeprecated;
import static org.jruby.api.Warn.warningDeprecated;
import static org.jruby.runtime.ThreadContext.*;
import static org.jruby.runtime.Visibility.*;
import static org.jruby.util.RubyStringBuilder.str;
import static org.jruby.util.RubyStringBuilder.types;
import static org.jruby.util.io.ChannelHelper.*;
import static org.jruby.util.io.EncodingUtils.encCodepointLength;
import static org.jruby.util.io.EncodingUtils.vperm;

@JRubyClass(name="IO", include="Enumerable")
public class RubyIO extends RubyObject implements IOEncodable, Closeable, Flushable {

    public static final ByteList PARAGRAPH_SEPARATOR = ByteList.create("\n\n");
    public static final String CLOSED_STREAM_MSG = "closed stream";

    public enum IOEvent {
        IO_READABLE(1), IO_PRIORITY(2), IO_WRITABLE(4);

        final int value;

        IOEvent(int value) {
            this.value = value;
        }

        int getValue() {
            return value;
        }
    }

    // This should only be called by this and RubyFile.
    // It allows this object to be created without a IOHandler.
    public RubyIO(Ruby runtime, RubyClass type) {
        super(runtime, type);
    }

    public RubyIO(Ruby runtime, OutputStream outputStream) {
        this(runtime, outputStream, true);
    }

    public RubyIO(Ruby runtime, OutputStream outputStream, boolean autoclose) {
        super(runtime, runtime.getIO());

        // We only want IO objects with valid streams (better to error now).
        if (outputStream == null) throw runtime.newRuntimeError("Opening null stream");

        openFile = MakeOpenFile();
        openFile.setFD(new ChannelFD(writableChannel(outputStream), runtime.getPosix(), runtime.getFilenoUtil()));
        openFile.setMode(OpenFile.WRITABLE | OpenFile.APPEND);
        openFile.setAutoclose(autoclose);
    }

    public RubyIO(Ruby runtime, InputStream inputStream) {
        super(runtime, runtime.getIO());

        if (inputStream == null) throw runtime.newRuntimeError("Opening null stream");

        openFile = MakeOpenFile();
        openFile.setFD(new ChannelFD(readableChannel(inputStream), runtime.getPosix(), runtime.getFilenoUtil()));
        openFile.setMode(OpenFile.READABLE);
    }

    public RubyIO(Ruby runtime, Channel channel) {
        this(runtime, runtime.getIO(), channel);
    }

    public RubyIO(Ruby runtime, RubyClass klass, Channel channel) {
        super(runtime, klass);
        var context = runtime.getCurrentContext();

        // We only want IO objects with valid streams (better to error now).
        if (channel == null) throw runtimeError(context, "Opening null channel");

        var posix = runtime.getPosix();
        initializeCommon(context, new ChannelFD(channel, posix, runtime.getFilenoUtil()),
                asFixnum(context, ModeFlags.oflagsFrom(posix, channel)), context.nil);
    }

    public RubyIO(Ruby runtime, ShellLauncher.POpenProcess process, IOOptions ioOptions) {
        super(runtime, runtime.getIO());

        var context = runtime.getCurrentContext();
        ioOptions = updateIOOptionsFromOptions(context, null, ioOptions);

        openFile = MakeOpenFile();

        setupPopen(context, ioOptions.getModeFlags(), process);
    }

    // MRI: prep_stdio
    public static RubyIO prepStdio(Ruby runtime, InputStream f, Channel c, int fmode, RubyClass klass, String path) {
        OpenFile fptr;
        RubyIO io = prepIO(runtime.getCurrentContext(), c, fmode | OpenFile.PREP | EncodingUtils.DEFAULT_TEXTMODE, klass, path);

        fptr = io.getOpenFileChecked();

        // If we can't use native IO, always force stdio to expected fileno.
        if (!runtime.getPosix().isNative() || Platform.IS_WINDOWS) {
            // Use standard stdio filenos if we're using System.in et al.
            if (f == System.in) {
                fptr.fd().realFileno = 0;
            }
        }

        prepStdioEcflags(fptr, fmode);
        fptr.stdio_file = f;

        // We checkTTY again here because we're using stdout/stdin to indicate this is stdio
        return recheckTTY(runtime, fptr, io);
    }

    // MRI: prep_stdio
    public static RubyIO prepStdio(Ruby runtime, OutputStream f, Channel c, int fmode, RubyClass klass, String path) {
        OpenFile fptr;
        RubyIO io = prepIO(runtime.getCurrentContext(), c, fmode | OpenFile.PREP | EncodingUtils.DEFAULT_TEXTMODE, klass, path);

        fptr = io.getOpenFileChecked();

        // If we can't use native IO, always force stdio to expected fileno.
        if (!runtime.getPosix().isNative() || Platform.IS_WINDOWS) {
            // Use standard stdio filenos if we're using System.in et al.
            if (f == System.out) {
                fptr.fd().realFileno = 1;
            } else if (f == System.err) {
                fptr.fd().realFileno = 2;
            }
        }

        prepStdioEcflags(fptr, fmode);
        fptr.stdio_file = f;

        return recheckTTY(runtime, fptr, io);
    }

    private static RubyIO recheckTTY(Ruby runtime, OpenFile fptr, RubyIO io) {
        // We checkTTY again here because we're using stdout/stdin to indicate this is stdio
        fptr.checkTTY();

        return io;
    }

    // MRI: part of prep_stdio
    private static void prepStdioEcflags(OpenFile fptr, int fmode) {
        boolean locked = fptr.lock();
        try {
            fptr.encs.ecflags |= EncodingUtils.ECONV_DEFAULT_NEWLINE_DECORATOR;
            if (EncodingUtils.TEXTMODE_NEWLINE_DECORATOR_ON_WRITE != 0) {
                fptr.encs.ecflags |= EncodingUtils.TEXTMODE_NEWLINE_DECORATOR_ON_WRITE;
                if ((fmode & OpenFile.READABLE) != 0) {
                    fptr.encs.ecflags |= EConvFlags.UNIVERSAL_NEWLINE_DECORATOR;
                }
            }
        } finally {
            if (locked) fptr.unlock();
        }
    }

    // MRI: prep_io
    private static RubyIO prepIO(ThreadContext context, Channel fd, int fmode, RubyClass klass, String path) {
        OpenFile fp;
        RubyIO io = (RubyIO)klass.allocate(context);

        fp = io.MakeOpenFile();
        fp.setChannel(fd);
        // Can we determine this?
//        if (Platform.IS_CYGWIN) {
//            if (!runtime.getPosix().isatty(fd)) {
//                fmode |= OpenFile.BINMODE;
                // TODO: setmode O_BINARY means what via NIO?
//                setmode(fd, OpenFlags.O_BINARY);
//            }
//        }
        fp.setMode(fmode);
        fp.checkTTY();
        if (path != null) fp.setPath(path);
//        rb_update_max_fd(fd);

        return io;
    }

    public static RubyIO newIO(Ruby runtime, Channel channel) {
        return new RubyIO(runtime, channel);
    }

    public OpenFile getOpenFile() {
        return openFile;
    }

    public OpenFile getOpenFileChecked() {
        checkInitialized();
        openFile.checkClosed();
        return openFile;
    }

    // MRI: rb_io_get_fptr
    public OpenFile getOpenFileInitialized() {
        checkInitialized();
        return openFile;
    }

    /*
     * We use FILE versus IO to match T_FILE in MRI.
     */
    @Override
    public ClassIndex getNativeClassIndex() {
        return ClassIndex.FILE;
    }

    public static RubyClass createIOClass(ThreadContext context, RubyClass Object, RubyModule Enumerable) {
        RubyClass IO = defineClass(context, "IO", Object, RubyIO::new).
                reifiedClass(RubyIO.class).
                kindOf(new RubyModule.JavaClassKindOf(RubyIO.class)).
                classIndex(ClassIndex.IO).
                include(context, Enumerable).
                defineMethods(context, RubyIO.class).
                // Constants for seek
                defineConstant(context, "SEEK_SET", asFixnum(context, PosixShim.SEEK_SET)).
                defineConstant(context, "SEEK_CUR", asFixnum(context, PosixShim.SEEK_CUR)).
                defineConstant(context, "SEEK_END", asFixnum(context, PosixShim.SEEK_END)).

                defineConstant(context, "READABLE", asFixnum(context, IOEvent.IO_READABLE.getValue())).
                defineConstant(context, "WRITABLE", asFixnum(context, IOEvent.IO_WRITABLE.getValue())).
                defineConstant(context, "PRIORITY", asFixnum(context, IOEvent.IO_PRIORITY.getValue()));

        IO.defineModuleUnder(context, "WaitReadable");
        IO.defineModuleUnder(context, "WaitWritable");

        return IO;
    }

    /**
     * Represents a Ruby IO::TimeoutErrorError as a throwable Java exception.
     *
     * @see RubyIOTimeoutError
     */
    public static class TimeoutError extends IOError {
        public TimeoutError(String message, RubyIOTimeoutError exception) {
            super(message, exception);
        }
    }

    @JRubyClass(name="TimeoutError", parent="IOError")
    public static class RubyIOTimeoutError extends RubyIOError {
        protected RubyIOTimeoutError(Ruby runtime, RubyClass exceptionClass) {
            super(runtime, exceptionClass);
        }

        static RubyClass define(ThreadContext context, RubyClass IO, RubyClass IOError) {
            return IO.defineClassUnder(context, "TimeoutError", IOError, RubyIOTimeoutError::new);
        }

        @Override
        protected TimeoutError constructThrowable(String message) {
            return new TimeoutError(message, this);
        }

        public static RubyIOTimeoutError newIOTimeoutError(Ruby runtime, String message) {
            return ((RubyIOTimeoutError) ((RubyClass) runtime.getClassFromPath("IO::TimeoutError")).newInstance(runtime.getCurrentContext(), RubyString.newString(runtime, message)));
        }
    }

    public OutputStream getOutStream() {
        return new OutputStream() {
            final Ruby runtime = getRuntime();

            @Override
            public void write(int b) throws IOException {
                RubyIO.this.write(runtime.getCurrentContext(), b);
            }

            @Override
            public void write(byte[] b) throws IOException {
                RubyIO.this.write(runtime.getCurrentContext(), b, 0, b.length, ASCIIEncoding.INSTANCE);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                RubyIO.this.write(runtime.getCurrentContext(), b, off, len, ASCIIEncoding.INSTANCE);
            }

            @Override
            public void flush() throws IOException {
                RubyIO.this.flush(runtime.getCurrentContext());
            }

            @Override
            public void close() throws IOException {
                RubyIO.this.close();
            }
        };
    }

    public InputStream getInStream() {
        return new InputStream() {
            final Ruby runtime = getRuntime();

            @Override
            public int read() throws IOException {
                return getByte(runtime.getCurrentContext());
            }

            @Override
            public int read(byte[] b) throws IOException {
                return read(b, 0, b.length);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return RubyIO.this.read(runtime.getCurrentContext(), b, off, len);
            }

            @Override
            public long skip(long n) throws IOException {
                return doSeek(runtime.getCurrentContext(), n, PosixShim.SEEK_CUR);
            }

            @Override
            public int available() throws IOException {
                if (RubyIO.this instanceof RubyFile) {
                    ThreadContext context = runtime.getCurrentContext();
                    long size = ((RubyFile) RubyIO.this).getSize(context);
                    if (size == 0) return 0;
                    if (size >= 0) return (int) (size - pos(context).getValue());
                }
                return 0;
            }

            @Override
            public void close() throws IOException {
                RubyIO.this.close();
            }
        };
    }

    /**
     * Get the underlying channel from this IO object. Note that IO buffers data internally, so the channel returned
     * here may have been read into those buffers. If the channel and the IO are both being used at the same time, the
     * stream will get out of sync.
     *
     * @return the underlying channel for this IO
     */
    public Channel getChannel() {
        // FIXME: Do we want to make a faux channel that is backed by IO's buffering? Or turn buffering off?
        return getOpenFileChecked().channel();
    }

    // io_reopen
    protected RubyIO reopenIO(ThreadContext context, RubyIO nfile) {
        OpenFile fptr, orig;
        ChannelFD fd, fd2;
        long pos = 0;

        nfile = TypeConverter.ioGetIO(context.runtime, nfile);
        fptr = getOpenFileChecked();
        orig = nfile.getOpenFileChecked();

        if (fptr == orig) return this;
        if (fptr.IS_PREP_STDIO()) {
            if ((fptr.stdio_file == System.in && !orig.isReadable()) ||
                    (fptr.stdio_file == System.out && !orig.isWritable()) ||
                    (fptr.stdio_file == System.err && !orig.isWritable())) {
                throw argumentError(context, fptr.PREP_STDIO_NAME() + " can't change access mode from \"" +
                        fptr.getModeAsString(context) + "\" to \"" + orig.getModeAsString(context) + "\"");
            }
        }

        Ruby runtime = context.runtime;
        // FIXME: three lock acquires...trying to reduce risk of deadlock, but not sure it's possible.

        boolean locked = fptr.lock();
        try {
            if (fptr.isWritable()) {
                if (fptr.io_fflush(context) < 0)
                    throw runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            } else {
                fptr.tell(context);
            }
        } finally {
            if (locked) fptr.unlock();
        }

        locked = orig.lock();
        try {
            if (orig.isReadable()) {
                pos = orig.tell(context);
            }
            if (orig.isWritable()) {
                if (orig.io_fflush(context) < 0)
                    throw runtime.newErrnoFromErrno(orig.errno(), fptr.getPath());
            }
        } finally {
            if (locked) orig.unlock();
        }

        /* copy rb_io_t structure */
        // NOTE: MRI does not copy sync here, but I can find no way to make stdout/stderr stay sync through a reopen
        locked = fptr.lock();
        boolean locked2 = orig.lock(); // TODO: This WILL deadlock if two threads try to reopen the same IOs in opposite directions. Fix?
        try {
            fptr.setMode(orig.getMode() | (fptr.getMode() & (OpenFile.PREP | OpenFile.SYNC)));
            fptr.setProcess(orig.getProcess());
            fptr.setLineNumber(orig.getLineNumber());
            if (orig.getPath() != null) fptr.setPath(orig.getPath());
            else if (!fptr.IS_PREP_STDIO()) fptr.setPath(null);
            fptr.setFinalizer(orig.getFinalizer());

            // TODO: unsure what to do here
            //        #if defined (__CYGWIN__) || !defined(HAVE_FORK)
            //        if (fptr->finalize == pipe_finalize)
            //            pipe_add_fptr(fptr);
            //        #endif

            fd = fptr.fd();
            fd2 = orig.fd();
            if (fd != fd2) {
                if (fptr.IS_PREP_STDIO() || fd.bestFileno() <= 2 || fptr.stdio_file == null) {
                    /* need to keep FILE objects of stdin, stdout and stderr */
                    checkReopenCloexecDup2(runtime, orig, fd2, fd);
                    //                rb_update_max_fd(fd);
                    fptr.setFD(fd);

                    //                // MRI does not do this, but we seem to need to set some types of channels to sync if they
                    //                // are reopened as stdout/stderr.
                    //                if (fptr.stdio_file == System.out || fptr.stdio_file == System.err) {
                    //                    fd.chFile.force();
                    //                }
                } else {
                    if (fptr.stdio_file != null) try {
                        fptr.stdio_file.close();
                    } catch (IOException ioe) {
                    }
                    fptr.clearStdio();
                    fptr.setFD(null);
                    checkReopenCloexecDup2(runtime, orig, fd2, fd);
                    //                rb_update_max_fd(fd);
                    fptr.setFD(fd);
                }
                // TODO: clear interrupts waiting on this IO?
                //            rb_thread_fd_close(fd);
                if (orig.isReadable() && pos >= 0) {
                    fptr.checkReopenSeek(context, runtime, pos);
                    orig.checkReopenSeek(context, runtime, pos);
                }
            }

            if (fptr.isBinmode()) {
                setBinmode();
            }
        } finally {
            if (locked2) orig.unlock();
            if (locked) fptr.unlock();
        }

        // We simply can't do this and still have real concrete types under RubyIO
//        setMetaClass(nfile.getMetaClass());
        return this;
    }

    private void checkReopenCloexecDup2(Ruby runtime, OpenFile orig, ChannelFD oldfd, ChannelFD newfd) {
        OpenFile.cloexecDup2(new PosixShim(runtime), oldfd, newfd);
    }

    // rb_io_binmode
    private void setBinmode() {
        OpenFile fptr;

        fptr = getOpenFileChecked();
        boolean locked = fptr.lock();
        try {
            if (fptr.readconv != null)
                fptr.readconv.binmode();
            if (fptr.writeconv != null)
                fptr.writeconv.binmode();
            fptr.setBinmode();
            fptr.clearTextMode();
            fptr.writeconvPreEcflags &= ~EConvFlags.NEWLINE_DECORATOR_MASK;
            if (OpenFlags.O_BINARY.defined()) {
                // TODO: Windows
                //            if (fptr.readconv == null) {
                //                SET_BINARY_MODE_WITH_SEEK_CUR(fptr);
                //            }
                //            else {
                // TODO: setmode O_BINARY means what via NIO?
                //                setmode(fptr->fd, O_BINARY);
                //            }
            }
        } finally {
            if (locked) fptr.unlock();
        }
    }

    // MRI: rb_io_reopen
    @JRubyMethod(name = "reopen", required = 1, optional = 1, checkArity = false)
    public IRubyObject reopen(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 2);

        final Ruby runtime = context.runtime;
        final IRubyObject nil = context.nil;
        RubyIO file = this;
        IRubyObject fname = nil, nmode = nil, opt = nil;
        int[] oflags_p = {0};
        OpenFile fptr;

        switch (argc) {
            case 3:
                opt = TypeConverter.checkHashType(runtime, args[2]);
                if (opt == nil) throw argumentError(context, 3, 2);
            case 2:
                if (opt == nil) {
                    opt = TypeConverter.checkHashType(runtime, args[1]);
                    if (opt == nil) {
                        nmode = args[1];
                        opt = nil;
                    }
                } else {
                    nmode = args[1];
                }
            case 1:
                fname = args[0];
        }
        if (argc == 1) {
            IRubyObject tmp = TypeConverter.ioCheckIO(runtime, fname);
            if (tmp != nil) {
                return file.reopenIO(context, (RubyIO) tmp);
            }
        }

        fname = RubyFile.get_path(context, fname);
        // Not implemented
//        fname.checkTaint();
        fptr = file.openFile;
        if (fptr == null) {
            fptr = file.openFile = MakeOpenFile();
        }

        boolean locked = fptr.lock();
        try {
            if (nmode != nil || opt != nil) {
                ConvConfig convconfig = new ConvConfig();
                API.ModeAndPermission vmode_vperm = new API.ModeAndPermission(nmode, null);
                int[] fmode_p = {0};

                EncodingUtils.extractModeEncoding(context, convconfig, vmode_vperm, opt, oflags_p, fmode_p);
                if (fptr.IS_PREP_STDIO() &&
                        ((fptr.getMode() & OpenFile.READWRITE) & (fmode_p[0] & OpenFile.READWRITE)) !=
                                (fptr.getMode() & OpenFile.READWRITE)) {
                    throw argumentError(context, fptr.PREP_STDIO_NAME() + " can't change access mode from \"" +
                            fptr.getModeAsString(context) + "\" to \"" + OpenFile.getStringFromMode(fmode_p[0]));
                }
                fptr.setMode(fmode_p[0]);
                fptr.encs = convconfig;
            } else {
                oflags_p[0] = OpenFile.getModeFlagsAsIntFrom(fptr.getMode());
            }

            fptr.setPath(fname.toString());
            if (fptr.fd() == null) {
                fptr.setFD(sysopen(runtime, fptr.getPath(), oflags_p[0], 0666));
                fptr.clearStdio();
                return file;
            }

            if (fptr.isWritable()) {
                if (fptr.io_fflush(context) < 0)
                    throw runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            }
            fptr.rbuf.off = fptr.rbuf.len = 0;

            if (fptr.isStdio()) {
                // Logic here reopens the stdio FILE* with a new path and mode. For our purposes, we skip this
                // since we do not want to damage the stdio streams
                //            if (freopen(RSTRING_PTR(fptr.pathv), rb_io_oflags_modestr(oflags), fptr.stdio_file) == 0) {
                //                rb_sys_fail_path(fptr.pathv);
                //            }
                fptr.setFD(sysopen(runtime, fptr.getPath(), oflags_p[0], 0666));

//                fptr.fd = fileno(fptr.stdio_file);
                OpenFile.fdFixCloexec(fptr.posix, fptr.fd().realFileno);

                // This logic configures buffering (none, line, full) and buffer size to match the original stdio
                // stream associated with this IO. I don't believe we can do this.
                //                #ifdef USE_SETVBUF
                //                if (setvbuf(fptr.stdio_file, NULL, _IOFBF, 0) != 0)
                //                    rb_warn("setvbuf() can't be honoured for %"PRIsVALUE, fptr.pathv);
                //                #endif
                //                if (fptr.stdio_file == stderr) {
                //                    if (setvbuf(fptr.stdio_file, NULL, _IONBF, BUFSIZ) != 0)
                //                        rb_warn("setvbuf() can't be honoured for %"PRIsVALUE, fptr.pathv);
                //                }
                //                else if (fptr.stdio_file == stdout && isatty(fptr.fd)) {
                //                    if (setvbuf(fptr.stdio_file, NULL, _IOLBF, BUFSIZ) != 0)
                //                        rb_warn("setvbuf() can't be honoured for %"PRIsVALUE, fptr.pathv);
                //                }
            } else {
                ChannelFD tmpfd = sysopen(runtime, fptr.getPath(), oflags_p[0], 0666);
                Errno err = null;
                if (OpenFile.cloexecDup2(fptr.posix, tmpfd, fptr.fd()) < 0)
                    err = fptr.errno();

                if (err != null) {
                    throw runtime.newErrnoFromErrno(err, fptr.getPath());
                }
                fptr.setFD(tmpfd);
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return file;
    }

    public IRubyObject getline(ThreadContext context, IRubyObject separator) {
        return getlineImpl(context, separator, -1, false);
    }

    /**
     * getline using logic of gets.  If limit is -1 then read unlimited amount.
     *
     */
    public IRubyObject getline(ThreadContext context, IRubyObject separator, long limit) {
        return getlineImpl(context, separator, (int) limit, false);
    }

    /**
     * getline using logic of gets.  If limit is -1 then read unlimited amount.
     * mri: rb_io_getline_1 (mostly)
     */
    IRubyObject getlineImpl(ThreadContext context, IRubyObject rs, final int limit, final boolean chomp) {
        Ruby runtime = context.runtime;

        final OpenFile fptr = getOpenFileChecked();

        final boolean locked = fptr.lock();
        try {
            fptr.checkCharReadable(context);

            if (limit == 0) {
                return RubyString.newEmptyString(runtime, fptr.readEncoding(runtime));
            }

            RubyString str = null; Encoding enc;

            if (rs == context.nil && limit < 0) {
                str = (RubyString) fptr.readAll(context, 0, context.nil);
                if (str.size() == 0) return context.nil;
                if (chomp) str.chomp_bang(context, globalVariables(context).getDefaultSeparator());
            } else if (rs == globalVariables(context).getDefaultSeparator()
                    && limit < 0
                    && !fptr.needsReadConversion()
                    && (enc = fptr.readEncoding(runtime)).isAsciiCompatible()) {
                fptr.NEED_NEWLINE_DECORATOR_ON_READ_CHECK();
                return fptr.getlineFast(context, enc, this, chomp);
            }

            return getlineImplSlowPart(context, fptr, str, rs, limit, chomp);

        } finally {
            if (locked) fptr.unlock();
        }
    }

    // MRI rb_io_getline_0
    private IRubyObject getlineImplSlowPart(ThreadContext context, final OpenFile fptr,
        RubyString str, IRubyObject rs, final int limit, final boolean chomp) {

        Ruby runtime = context.runtime;

        boolean noLimit = false;

        // slow path logic
        int c, newline = -1;
        byte[] rsptrBytes = null;
        int rsptr = 0;
        int rslen = 0;
        boolean rspara = false;
        int extraLimit = 16;
        boolean chompCR = chomp;

        fptr.SET_BINARY_MODE();
        final Encoding enc = getReadEncoding();

        if (rs != context.nil) {
            RubyString rsStr = (RubyString) rs;
            ByteList rsByteList = rsStr.getByteList();
            rslen = rsByteList.getRealSize();
            if (rslen == 0) {
                rsptrBytes = PARAGRAPH_SEPARATOR.unsafeBytes();
                rsptr = PARAGRAPH_SEPARATOR.getBegin();
                rslen = 2;
                rspara = true;
                fptr.swallow(context, '\n');
                rs = null;
                if (!enc.isAsciiCompatible()) {
                    rs = RubyString.newUsAsciiStringShared(runtime, rsptrBytes, rsptr, rslen);
                    rs = EncodingUtils.strConvEnc(context, (RubyString) rs, null, enc);
                    rs.setFrozen(true);
                    rsStr = (RubyString) rs;
                    rsByteList = rsStr.getByteList();
                    rsptrBytes = rsByteList.getUnsafeBytes();
                    rsptr = rsByteList.getBegin();
                    rslen = rsByteList.getRealSize();
                }
                newline = '\n';
            } else if (enc.minLength() == 1) {
                rsptr = rsByteList.begin();
                rsptrBytes = rsByteList.getUnsafeBytes();
                newline = Byte.toUnsignedInt(rsptrBytes[rsptr + rslen - 1]);
            } else {
                rs = rsStr.encode(context, encodingService(context).convertEncodingToRubyEncoding(enc));
                rsStr = (RubyString) rs;
                rsByteList = rsStr.getByteList();
                rsptrBytes = rsByteList.getUnsafeBytes();
                rsptr = rsByteList.getBegin();
                rslen = rsByteList.getRealSize();
                int e = rsptr + rslen;
                int last = enc.prevCharHead(rsptrBytes, rsptr, e, e);
                int[] n = {0};
                newline = encCodepointLength(context, rsptrBytes, last, e, n, enc);
                if (last + n[0] != e) throw argumentError(context, "broken separator");
            }
            chompCR = chomp && newline == '\n' && rslen == enc.minLength();
        }

        final ByteList[] strPtr = { str != null ? str.getByteList() : null };
        final int[] limit_p = { limit };

        while ((c = fptr.appendline(context, newline, strPtr, limit_p, enc)) != OpenFile.EOF) {
            int s, p, pp, e;

            final byte[] strBytes = strPtr[0].getUnsafeBytes();
            final int realSize = strPtr[0].getRealSize();
            final int begin = strPtr[0].getBegin();

            if (c == newline) {
                if (realSize < rslen) continue;
                s = begin;
                e = s + realSize;
                p = e - rslen;
                if (!atCharacterBoundary(enc, strBytes, s, p, e)) continue;
                if (!rspara) rscheck(context, rsptrBytes, rslen, rs);
                if (ByteList.memcmp(strBytes, p, rsptrBytes, rsptr, rslen) == 0) {
                    if (chomp) {
                        if (chompCR && p > s && strBytes[p-1] == '\r') --p;
                        strPtr[0].length(p - s);
                    }
                    break;
                }
            }
            if (limit_p[0] == 0) {
                s = begin;
                p = s + realSize;
                pp = enc.prevCharHead(strBytes, s, p, p);
                if (extraLimit != 0 && pp != -1 &&
                        StringSupport.MBCLEN_NEEDMORE_P(StringSupport.preciseLength(enc, strBytes, pp, p))) {
                    limit_p[0] = 1;
                    extraLimit--;
                } else {
                    noLimit = true;
                    break;
                }
            }
        }
        // limit = limit_p[0];
        if (strPtr[0] != null) {
            if (str != null) {
                str.setValue(strPtr[0]);
            } else {
                str = runtime.newString(strPtr[0]);
            }
        }

        if (rspara && c != OpenFile.EOF) {
            // FIXME: This may block more often than it should, to clean up extraneous newlines
            fptr.swallow(context, '\n');
        }
        if (str != null) { // io_enc_str :
            str.setEncoding(enc);
        }

        if (str != null && !noLimit) fptr.incrementLineno(runtime, this);

        return str == null ? context.nil : str;
    }

    private static void rscheck(ThreadContext context, byte[] strBytes, int len, IRubyObject rs) {
        if (rs == null || rs.isNil()) return;
        if (((RubyString) rs).getByteList().getUnsafeBytes() != strBytes && ((RubyString) rs).getByteList().getRealSize() != len) {
            throw runtimeError(context, "rs modified");
        }
    }

    private static boolean atCharacterBoundary(Encoding enc, byte[] strBytes, int s, int p, int e) {
        return enc.leftAdjustCharHead(strBytes, s, p, e) == p;
    }

    // fptr->enc and codeconv->enc
    public Encoding getEnc() {
        return openFile.encs.enc;
    }

    // mri: io_read_encoding
    public Encoding getReadEncoding() {
        return openFile.readEncoding(getRuntime());
    }

    // fptr->enc2 and codeconv->enc2
    public Encoding getEnc2() {
        return openFile.encs.enc2;
    }

    // mri: io_input_encoding
    public Encoding getInputEncoding() {
        return openFile.inputEncoding(getRuntime());
    }

    // IO class methods.

    @JRubyMethod(name = "new", rest = true, meta = true, keywords = true)
    public static IRubyObject newInstance(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        RubyClass klass = (RubyClass)recv;

        if (block.isGiven()) {
            IRubyObject className = types(context.runtime, klass);

            warn(context, str(context.runtime, className, "::new() does not take block; use ", className, "::open() instead"));
        }

        return klass.newInstance(context, args, block);
    }

    @JRubyMethod(rest = true, meta = true, keywords = true)
    public static IRubyObject for_fd(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        RubyClass klass = (RubyClass)recv;

        return klass.newInstance(context, args, block);
    }

    private IRubyObject initializeCommon(ThreadContext context, int fileno, IRubyObject vmodeArg, IRubyObject opt) {
        Ruby runtime = context.runtime;

        ChannelFD fd;

        if (!FilenoUtil.isFake(fileno)) {
            // try using existing ChannelFD, then fall back on creating a new one
            fd = runtime.getFilenoUtil().getWrapperFromFileno(fileno);

            if (fd == null) {
                if (runtime.getPosix().isNative() && !Platform.IS_WINDOWS) {
                    fd = new ChannelFD(new NativeDeviceChannel(fileno), runtime.getPosix(), runtime.getFilenoUtil());
                } else {
                    // Native channels don't work quite right on Windows yet. Override standard io for better nonblocking support. See jruby/jruby#3625
                    switch (fileno) {
                        case 0:
                            fd = new ChannelFD(Channels.newChannel(runtime.getIn()), runtime.getPosix(), runtime.getFilenoUtil());
                            break;
                        case 1:
                            fd = new ChannelFD(Channels.newChannel(runtime.getOut()), runtime.getPosix(), runtime.getFilenoUtil());
                            break;
                        case 2:
                            fd = new ChannelFD(Channels.newChannel(runtime.getErr()), runtime.getPosix(), runtime.getFilenoUtil());
                            break;
                        default:
                            fd = new ChannelFD(new NativeDeviceChannel(fileno), runtime.getPosix(), runtime.getFilenoUtil());
                            break;
                    }
                }
            }
        } else {
            fd = runtime.getFilenoUtil().getWrapperFromFileno(fileno);

            if (fd == null) throw runtime.newErrnoEBADFError();
        }

        if (!fd.ch.isOpen()) {
            throw runtime.newErrnoEBADFError();
        }

        return initializeCommon(context, fd, vmodeArg, opt);
    }

    private IRubyObject initializeCommon(ThreadContext context, ChannelFD fd, IRubyObject vmodeArg, IRubyObject opt) {
        Ruby runtime = context.runtime;

        int ofmode;
        int[] oflags_p = {ModeFlags.RDONLY};

        if (opt != null && !opt.isNil() && !(opt instanceof RubyHash) && !(sites(context).respond_to_to_hash.respondsTo(context, opt, opt))) {
            throw argumentError(context, "last argument must be a hash!");
        }

        if (opt != null && !opt.isNil()) opt = opt.convertToHash();

        if (!fd.ch.isOpen()) throw runtime.newErrnoEBADFError();

        IRubyObject vperm = asFixnum(context, 0);
        API.ModeAndPermission pm = new API.ModeAndPermission(vmodeArg, vperm);
        int[] fmode_p = {0};
        ConvConfig convconfig = new ConvConfig();
        EncodingUtils.extractModeEncoding(context, convconfig, pm, opt, oflags_p, fmode_p);

        { // normally done with fcntl...which we *could* do too...but this is just checking read/write
            oflags_p[0] = ModeFlags.oflagsFrom(runtime.getPosix(), fd.ch);

            ofmode = ModeFlags.getOpenFileFlagsFor(oflags_p[0]);
            if (EncodingUtils.vmode(pm) == null || EncodingUtils.vmode(pm).isNil()) {
                fmode_p[0] = ofmode;
            } else if (((~ofmode & fmode_p[0]) & OpenFile.READWRITE) != 0) {
                throw runtime.newErrnoEINVALError();
            }
        }

        IRubyObject path = null;
        if (opt instanceof RubyHash optHash) {
            if (optHash.op_aref(context, asSymbol(context, "autoclose")) == context.fals) {
                fmode_p[0] |= OpenFile.PREP;
            }

            path = optHash.op_aref(context, asSymbol(context, "path"));
            if (!path.isNil()) {
                path = path.convertToString().newFrozen();
            }
        }

        // JRUBY-4650: Make sure we clean up the old data, if it's present.
        MakeOpenFile();

        openFile.setFD(fd);
        openFile.setMode(fmode_p[0]);
        openFile.encs = convconfig;
        openFile.clearCodeConversion();
        if (path != null) openFile.setPath(path.toString());

        openFile.checkTTY();
        switch (fd.bestFileno()) {
            case 0:
                openFile.stdio_file = System.in;
                break;
            case 1:
                openFile.stdio_file = System.out;
                break;
            case 2:
                openFile.stdio_file = System.err;
                break;
        }

        if (openFile.isBOM()) {
            EncodingUtils.ioSetEncodingByBOM(context, this);
        }

        return this;
    }

    @JRubyMethod(name = "initialize", visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject fileNumber, Block unused) {
        return initializeCommon(context, toInt(context, fileNumber), null, context.nil);
    }

    @JRubyMethod(name = "initialize", visibility = PRIVATE)
    public IRubyObject initialize(ThreadContext context, IRubyObject fileNumber, IRubyObject second, Block unused) {
        int fileno = toInt(context, fileNumber);
        IRubyObject vmode = null;
        IRubyObject options;
        IRubyObject hashTest = TypeConverter.checkHashType(context.runtime, second);
        if (hashTest instanceof RubyHash) {
            options = hashTest;
        } else {
            options = context.nil;
            vmode = second;
        }

        return initializeCommon(context, fileno, vmode, options);
    }

    @JRubyMethod(name = "initialize", visibility = PRIVATE, keywords = true)
    public IRubyObject initialize(ThreadContext context, IRubyObject fileNumber, IRubyObject modeValue, IRubyObject options, Block unused) {
        int callInfo = ThreadContext.resetCallInfo(context);
        int fileno = toInt(context, fileNumber);

        // TODO: MRI has a method name in ArgumentError. 
        // e.g. `for_fd': wrong number of arguments (given 3, expected 1..2)
        if (modeValue != null && !modeValue.isNil() && !(modeValue instanceof RubyInteger) && !(modeValue instanceof RubyString)) {
            throw argumentError(context, 3, 1, 2);
        }
        if (!hasKeywords(callInfo)) throw argumentError(context, 3, 1, 2);

        return initializeCommon(context, fileno, modeValue, options);
    }

    // Encoding processing
    protected IOOptions parseIOOptions(IRubyObject arg) {
        Ruby runtime = getRuntime();

        if (arg instanceof RubyFixnum fixnum) return newIOOptions(runtime, (int) fixnum.getValue());

        String modeString = arg.convertToString().toString();
        try {
            return new IOOptions(runtime, modeString);
        } catch (InvalidValueException ive) {
            throw argumentError(runtime.getCurrentContext(), "invalid access mode " + modeString);
        }
    }

    @JRubyMethod
    public IRubyObject external_encoding(ThreadContext context) {
        EncodingService encodingService = encodingService(context);

        if (openFile.encs.enc2 != null) return encodingService.getEncoding(openFile.encs.enc2);

        if (openFile.isWritable()) {
            return openFile.encs.enc == null ? context.nil : encodingService.getEncoding(openFile.encs.enc);
        }

        return encodingService.getEncoding(getReadEncoding());
    }

    @JRubyMethod
    public IRubyObject internal_encoding(ThreadContext context) {
        return openFile.encs.enc2 == null ?
                context.nil :
                encodingService(context).getEncoding(getReadEncoding());
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject encodingObj) {
        setEncoding(context, encodingObj, context.nil, context.nil);

        return this;
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject encodingString, IRubyObject internalEncoding) {
        IRubyObject opt = TypeConverter.checkHashType(context.runtime, internalEncoding);
        if (!opt.isNil()) {
            setEncoding(context, encodingString, context.nil, opt);
        } else {
            setEncoding(context, encodingString, internalEncoding, context.nil);
        }

        return this;
    }

    @JRubyMethod
    public IRubyObject set_encoding(ThreadContext context, IRubyObject encodingString, IRubyObject internalEncoding, IRubyObject options) {
        setEncoding(context, encodingString, internalEncoding, options);

        return this;
    }

    // MRI: rb_io_set_encoding_by_bom
    @JRubyMethod
    public IRubyObject set_encoding_by_bom(ThreadContext context) {
        OpenFile fptr;

        fptr = getOpenFile();
        if (!fptr.isBinmode()) {
            throw argumentError(context, "ASCII incompatible encoding needs binmode");
        }

        if (getEnc2() != null) {
            throw argumentError(context, "encoding conversion is set");
        } else if (getEnc() != null && getEnc() != ASCIIEncoding.INSTANCE) {
            throw argumentError(context, "encoding is set to " + getEnc() + " already");
        }

        if (EncodingUtils.ioSetEncodingByBOM(context, this) == null) return context.nil;

        return encodingService(context).getEncoding(getEnc());
    }

    // mri: io_encoding_set
    public void setEncoding(ThreadContext context, IRubyObject v1, IRubyObject v2, IRubyObject opt) {
        final IRubyObject nil = context.nil;
        IOEncodable.ConvConfig holder = new IOEncodable.ConvConfig();
        int ecflags = openFile.encs.ecflags;
        IRubyObject[] ecopts_p = { nil };

        if (v2 != nil) {
            holder.enc2 = EncodingUtils.rbToEncoding(context, v1);
            IRubyObject tmp = v2.checkStringType();

            if (tmp != nil) {
                RubyString internalAsString = (RubyString) tmp;

                // No encoding '-'
                if (isDash(internalAsString)) {
                    /* Special case - "-" => no transcoding */
                    holder.enc = holder.enc2;
                    holder.enc2 = null;
                } else {
                    holder.enc = EncodingUtils.rbToEncoding(context, internalAsString);
                }

                if (holder.enc == holder.enc2) {
                    /* Special case - "-" => no transcoding */
                    holder.enc2 = null;
                }
            } else {
                holder.enc = EncodingUtils.rbToEncoding(context, v2);

                if (holder.enc == holder.enc2) {
                    /* Special case - "-" => no transcoding */
                    holder.enc2 = null;
                }
            }
            if (holder.enc2 == ASCIIEncoding.INSTANCE) {
                /* If external is ASCII-8BIT, no transcoding */
                holder.enc = holder.enc2;
                holder.enc2 = null;
            }
            EncodingUtils.SET_UNIVERSAL_NEWLINE_DECORATOR_IF_ENC2(holder.getEnc2(), ecflags);
            ecflags = EncodingUtils.econvPrepareOptions(context, opt, ecopts_p, ecflags);
        } else {
            if (v1.isNil()) {
                EncodingUtils.ioExtIntToEncs(context, holder, null, null, 0);
                EncodingUtils.SET_UNIVERSAL_NEWLINE_DECORATOR_IF_ENC2(holder.getEnc2(), ecflags);
                ecopts_p[0] = context.nil;
            } else {
                IRubyObject tmp = v1.checkStringType();
                if (tmp != nil && EncodingUtils.encAsciicompat(EncodingUtils.encGet(context, tmp))) {
                    EncodingUtils.parseModeEncoding(context, holder, tmp.asJavaString(), null);
                    EncodingUtils.SET_UNIVERSAL_NEWLINE_DECORATOR_IF_ENC2(holder.getEnc2(), ecflags);
                    ecflags = EncodingUtils.econvPrepareOptions(context, opt, ecopts_p, ecflags);
                } else {
                    EncodingUtils.ioExtIntToEncs(context, holder, EncodingUtils.rbToEncoding(context, v1), null, 0);
                    EncodingUtils.SET_UNIVERSAL_NEWLINE_DECORATOR_IF_ENC2(holder.getEnc2(), ecflags);
                }
            }
            // enc, enc2 should be set by now
        }

        int[] fmode_p = { openFile.getMode() };
        EncodingUtils.validateEncodingBinmode(context, fmode_p, ecflags, holder);
        openFile.setMode(fmode_p[0]);

        openFile.encs.enc = holder.enc;
        openFile.encs.enc2 = holder.enc2;
        openFile.encs.ecflags = ecflags;
        openFile.encs.ecopts = ecopts_p[0];

        openFile.clearCodeConversion();
    }

    // rb_io_s_open, 2014/5/16
    @JRubyMethod(required = 1, rest = true, checkArity = false, meta = true, keywords = true)
    public static IRubyObject open(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        IRubyObject io = ((RubyClass)recv).newInstance(context, args, Block.NULL_BLOCK);

        return ensureYieldClose(context, io, block);
    }

    public static IRubyObject ensureYieldClose(ThreadContext context, IRubyObject port, Block block) {
        if (block.isGiven()) {
            try {
                return block.yield(context, port);
            } finally {
                ioClose(context, port);
            }
        }
        return port;
    }

    @Deprecated
    public static IRubyObject sysopen(IRubyObject recv, IRubyObject[] args, Block block) {
        return sysopen(((RubyBasicObject) recv).getCurrentContext(), recv, args, block);
    }

    // rb_io_s_sysopen
    @JRubyMethod(name = "sysopen", required = 1, optional = 2, checkArity = false, meta = true)
    public static IRubyObject sysopen(ThreadContext context, IRubyObject recv, IRubyObject[] argv, Block block) {
        int argc = Arity.checkArgumentCount(context, argv, 1, 3);

        Ruby runtime = context.runtime;
        IRubyObject vmode, vperm;
        vmode = vperm = context.nil;
        IRubyObject intmode;
        int oflags;

        switch (argc) {
            case 3:
                vperm = argv[2];
            case 2:
                vmode = argv[1];
        }
        IRubyObject fname = RubyFile.get_path(context, argv[0]);

        if (vmode.isNil())
            oflags = OpenFlags.O_RDONLY.intValue();
        else if (!(intmode = checkToInteger(context, vmode)).isNil())
            oflags = ((RubyInteger) intmode).asInt(context);
        else {
            vmode = vmode.convertToString();
            oflags = OpenFile.ioModestrOflags(context, vmode.toString());
        }
        int perm = (vperm.isNil()) ? 0666 : toInt(context, vperm);

        StringSupport.checkStringSafety(context.runtime, fname);
        fname = ((RubyString)fname).dupFrozen();
        ChannelFD fd = sysopen(runtime, fname.toString(), oflags, perm);
        return asFixnum(context, fd.bestFileno(true));
    }

    public static class Sysopen {
        public String fname;
        public int oflags;
        public int perm;
        public Errno errno;
    }

    // rb_sysopen
    protected static ChannelFD sysopen(Ruby runtime, String fname, int oflags, int perm) {
        ChannelFD fd;
        Sysopen data = new Sysopen();

        data.fname = fname;
        data.oflags = oflags;
        data.perm = perm;

        fd = sysopenInternal(runtime, data);
        if (fd == null) {
            if (data.errno != null) {
                throw runtime.newErrnoFromErrno(data.errno, fname);
            } else {
                throw runtime.newSystemCallError(fname);
            }
        }
        return fd;
    }

    // rb_sysopen_internal
    private static ChannelFD sysopenInternal(Ruby runtime, Sysopen data) {
        ChannelFD fd;
        // TODO: thread eventing as in MRI
        fd = sysopenFunc(runtime, data);
//        if (0 <= fd)
//            rb_update_max_fd(fd);
        return fd;
    }

    // sysopen_func
    private static ChannelFD sysopenFunc(Ruby runtime, Sysopen data) {
        return cloexecOpen(runtime, data);
    }

    // rb_cloexec_open
    public static ChannelFD cloexecOpen(Ruby runtime, Sysopen data) {
        Channel ret = null;
        if (OpenFlags.O_CLOEXEC.defined()) {
            data.oflags |= OpenFlags.O_CLOEXEC.intValue();
        } else { // #elif defined O_NOINHERIT
//            flags |= O_NOINHERIT;
        }
        PosixShim shim = new PosixShim(runtime);
        ret = shim.open(runtime.getCurrentDirectory(), data.fname, data.oflags, data.perm);
        if (ret == null) {
            data.errno = shim.getErrno();
            return null;
        }
        ChannelFD fd = new ChannelFD(ret, runtime.getPosix(), runtime.getFilenoUtil(), data.oflags);
        if (fd.realFileno > 0 && runtime.getPosix().isNative() && !Platform.IS_WINDOWS) {
            OpenFile.fdFixCloexec(shim, fd.realFileno);
        }

        return fd;
    }

    // MRI: rb_io_autoclose_p
    public boolean isAutoclose() {
        OpenFile fptr;
        fptr = getOpenFileChecked();
        return fptr.isAutoclose();
    }

    // MRI: rb_io_set_autoclose
    public void setAutoclose(boolean autoclose) {
        OpenFile fptr;
        fptr = getOpenFileChecked();
        fptr.setAutoclose(autoclose);
    }

    @JRubyMethod(name = "autoclose?")
    public IRubyObject autoclose(ThreadContext context) {
        return asBoolean(context, isAutoclose());
    }

    @JRubyMethod(name = "autoclose=")
    public IRubyObject autoclose_set(ThreadContext context, IRubyObject autoclose) {
        setAutoclose(autoclose.isTrue());
        return context.nil;
    }

    /**
     * @return
     * @deprecated Use {@link RubyIO#binmode(ThreadContext)} instead.
     */
    @Deprecated(since = "10.0")
    public IRubyObject binmode() {
        return binmode(getCurrentContext());
    }

        // MRI: rb_io_binmode_m
    @JRubyMethod(name = "binmode")
    public IRubyObject binmode(ThreadContext context) {
        setAscii8bitBinmode();

        RubyIO write_io = GetWriteIO();
        if (write_io != this)
             write_io.setAscii8bitBinmode();

        return this;
    }

    // MRI: rb_io_binmode_p
    @JRubyMethod(name = "binmode?")
    public IRubyObject op_binmode(ThreadContext context) {
        return asBoolean(context, getOpenFileChecked().isBinmode());
    }

    // rb_io_syswrite
    @JRubyMethod(name = "syswrite")
    public IRubyObject syswrite(ThreadContext context, IRubyObject str) {
        OpenFile fptr;
        long n;

        if (!(str instanceof RubyString)) str = str.asString();

        RubyIO io = GetWriteIO();
        fptr = io.getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkWritable(context);

            str = str.convertToString().newFrozen();

            if (fptr.wbuf.len != 0) warn(context, "syswrite for buffered IO");

            ByteList strByteList = ((RubyString) str).getByteList();
            n = OpenFile.writeInternal(context, fptr, strByteList.unsafeBytes(), strByteList.begin(), strByteList.getRealSize());

            if (n == -1) throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
        } finally {
            if (locked) fptr.unlock();
        }

        return asFixnum(context, n);
    }

    // MRI: rb_io_write_nonblock
    @JRubyMethod(name = "write_nonblock")
    public IRubyObject write_nonblock(ThreadContext context, IRubyObject arg0) {
        boolean exception = ArgsUtil.extractKeywordArg(context, "exception", arg0) != context.fals;

        return ioWriteNonblock(context, context.runtime, arg0, !exception);
    }

    @JRubyMethod(name = "write_nonblock")
    public IRubyObject write_nonblock(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        boolean exception = ArgsUtil.extractKeywordArg(context, "exception", arg1) != context.fals;

        return ioWriteNonblock(context, context.runtime, arg0, !exception);
    }

    // MRI: io_write_nonblock
    private IRubyObject ioWriteNonblock(ThreadContext context, Ruby runtime, IRubyObject str, boolean no_exception) {
        OpenFile fptr;
        long n;

        if (!(str instanceof RubyString))
            str = str.asString();

        RubyIO io = GetWriteIO();
        fptr = io.getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkWritable(context);

            if (fptr.io_fflush(context) < 0)
                throw runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());

            fptr.setNonblock(runtime);

            ByteList strByteList = ((RubyString) str).getByteList();
            n = fptr.posix.write(fptr.fd(), strByteList.unsafeBytes(), strByteList.begin(), strByteList.getRealSize(), true);

            if (n == -1) {
                if (fptr.posix.getErrno() == Errno.EWOULDBLOCK || fptr.posix.getErrno() == Errno.EAGAIN) {
                    if (no_exception) return asSymbol(context, "wait_writable");
                    throw runtime.newErrnoEAGAINWritableError("write would block");
                }
                throw runtime.newErrnoFromErrno(fptr.posix.getErrno(), fptr.getPath());
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return asFixnum(context, n);
    }

    public RubyIO GetWriteIO() {
        RubyIO writeIO;
        checkInitialized();
        writeIO = openFile.tiedIOForWriting;
        if (writeIO != null) {
            return writeIO;
        }
        return this;
    }

    private void checkInitialized() {
        if (openFile == null) {
            throw getRuntime().newIOError("uninitialized stream");
        }
    }

    /**
     * Ruby method IO#write(str), equivalent to io_write_m.
     *
     * @param context the current context
     * @param str the string to write
     */
    @JRubyMethod(name = "write")
    public IRubyObject write(ThreadContext context, IRubyObject str) {
        return write(context, str, false);
    }

    /**
     * Ruby method IO#write(str, ...), equivalent to io_write_m.
     *
     * @param context the current context
     * @param arg0 the first string to write
     * @param arg1 the second string to write
     */
    @JRubyMethod(name = "write")
    public IRubyObject write2(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        long acc = toLong(context, write(context, arg0, false));
        acc += toLong(context, write(context, arg1, false));
        return asFixnum(context, acc);
    }

    /**
     * Ruby method IO#write(str, ...), equivalent to io_write_m.
     *
     * @param context the current context
     * @param arg0 the first string to write
     * @param arg1 the second string to write
     * @param arg2 the third string to write
     */
    @JRubyMethod(name = "write")
    public IRubyObject write3(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        long acc = toLong(context, write(context, arg0, false));
        acc += toLong(context, write(context, arg1, false));
        acc += toLong(context, write(context, arg2, false));
        return asFixnum(context, acc);
    }

    /**
     * Ruby method IO#write(str, ...), equivalent to io_write_m.
     *
     * @param context the current context
     * @param args the strings to write
     */
    @JRubyMethod(name = "write", rest = true)
    public IRubyObject write(ThreadContext context, IRubyObject[] args) {
        long acc = 0L;
        for (IRubyObject s : args) {
            IRubyObject write = write(context, s, false);
            long num2long = toLong(context, write);
            acc = acc + num2long;
        }
        return asFixnum(context, acc);
    }

    /**
     * Write a single byte to this IO's write target.
     *
     * @param context the current context
     * @param ch the byte to write, as an int
     * @return the count of bytes written
     */
    public final IRubyObject write(ThreadContext context, int ch) {
        RubyString str = newSharedString(context, RubyInteger.singleCharByteList((byte) ch));
        return write(context, str, false);
    }

    /**
     * Write a single byte to this IO's write target but do not return the number of bytes written (it will be 1).
     *
     * This version does not dig out any wrapped write IO (bytes will be written to this IO).
     *
     * @param context the current context
     * @param ch the byte to write, as an int
     */
    public final void write(ThreadContext context, byte ch) {
        ByteList bytes = RubyInteger.singleCharByteList(ch);

        write(context, bytes.unsafeBytes(), bytes.begin(), bytes.realSize(), bytes.getEncoding(), false);
    }

    /**
     * Write the given range of bytes to this IO's write target.
     *
     * Equivalent to io_write.
     *
     * @param context the current context
     * @param str the string to write
     * @param nosync whether to write without syncing
     * @return the count of bytes written
     */
    public IRubyObject write(ThreadContext context, IRubyObject str, boolean nosync) {
        OpenFile fptr;
        long n;
        IRubyObject tmp;

        RubyIO io = GetWriteIO();

        RubyString string = str.asString();
        tmp = TypeConverter.ioCheckIO(context.runtime, io);
        if (tmp == context.nil) {
	        /* port is not IO, call write method for it. */
            return sites(context).write.call(context, io, io, string);
        }
        io = (RubyIO) tmp;
        if (string.isEmpty()) return asFixnum(context, 0);

        fptr = io.getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr = io.getOpenFileChecked();
            fptr.checkWritable(context);

            n = fptr.fwrite(context, string, nosync);
            if (n == -1) throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
        } finally {
            if (locked) fptr.unlock();
        }

        return asFixnum(context, n);
    }

    /**
     * Write the given range of bytes to this IO.
     *
     * Equivalent to io_write_m with source bytes and no digging out any wrapped write IO (bytes will be written to this
     * IO).
     *
     * @param context the current context
     * @param bytes the bytes to write
     * @param start start offset for writing
     * @param length length to write
     * @param encoding encoding of the bytes (will not be verified)
     * @return the count of bytes written
     */
    public int write(ThreadContext context,  byte[] bytes, int start, int length, Encoding encoding) {
        return write(context, bytes, start, length, encoding, false);
    }

    /**
     * Write the given range of bytes to this IO.
     *
     * Equivalent to io_write with source bytes and no digging out any wrapped write IO (bytes will be written to this
     * IO.
     *
     * @param context the current context
     * @param bytes the bytes to write
     * @param start start offset for writing
     * @param length length to write
     * @param encoding encoding of the bytes (will not be verified)
     * @param nosync whether to write without syncing
     * @return the count of bytes written
     */
    public int write(ThreadContext context, byte[] bytes, int start, int length, Encoding encoding, boolean nosync) {
        if (length == 0) return 0;

        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr = getOpenFileChecked();
            fptr.checkWritable(context);

            int n = fptr.fwrite(context, bytes, start, length, encoding, nosync);

            if (n == -1) throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());

            return n;
        } finally {
            if (locked) fptr.unlock();
        }

    }

    /**
     * Same as {@link #write(ThreadContext, byte[], int, int, Encoding, boolean)} but context will be retrieved.
     *
     * Heavy use should retrieve context once and call the original method to avoid thread-local overhead.
     *
     * @param bytes the bytes to write
     * @param start start offset for writing
     * @param length length to write
     * @param encoding encoding of the bytes (will not be verified)
     * @param nosync whether to write without syncing
     * @return the count of bytes written
     */
    public int write(byte[] bytes, int start, int length, Encoding encoding, boolean nosync) {
        return write(getRuntime().getCurrentContext(), bytes, start, length, encoding, nosync);
    }

    /**
     * Same as {@link #write(ThreadContext, byte[], int, int, Encoding, boolean)} but context will be retrieved and
     * nosync defaults to false.
     *
     * Heavy use should retrieve context once and call the original method to avoid thread-local overhead.
     *
     * @param bytes the bytes to write
     * @param start start offset for writing
     * @param length length to write
     * @param encoding encoding of the bytes (will not be verified)
     * @return the count of bytes written
     */
    public int write(byte[] bytes, int start, int length, Encoding encoding) {
        return write(bytes, start, length, encoding, false);
    }


    /**
     * Same as {@link #write(ThreadContext, byte[], int, int, Encoding, boolean)} but context will be retrieved nosync
     * defaults to false, and the entire byte array will be written.
     *
     * Heavy use should retrieve context once and call the original method to avoid thread-local overhead.
     *
     * @param bytes the bytes to write
     * @param encoding encoding of the bytes (will not be verified)
     * @return the count of bytes written
     */
    public int write(byte[] bytes, Encoding encoding) {
        return write(bytes, 0, bytes.length, encoding);
    }

    /**
     * Same as {@link #write(ThreadContext, byte)} but context will be retrieved.
     *
     * Heavy use should retrieve context once and call the original method to avoid thread-local overhead.
     *
     * @param bite the byte to write, as an int
     */
    public void write(int bite) {
        write(getRuntime().getCurrentContext(), (byte) bite);
    }

    /** rb_io_addstr
     *
     */
    @JRubyMethod(name = "<<")
    public IRubyObject op_append(ThreadContext context, IRubyObject anObject) {
        // Claims conversion is done via 'to_s' in docs.
        sites(context).write.call(context, this, this, anObject);

        return this;
    }

    @JRubyMethod(name = "fileno", alias = "to_i")
    public RubyFixnum fileno(ThreadContext context) {
        return asFixnum(context, getOpenFileChecked().getFileno());
    }

    /** Returns the current line number.
     *
     * @return the current line number.
     */
    @JRubyMethod(name = "lineno")
    public RubyFixnum lineno(ThreadContext context) {
        return asFixnum(context, getOpenFileChecked().getLineNumber());
    }

    /** Sets the current line number.
     *
     * @param newLineNumber The new line number.
     */
    @JRubyMethod(name = "lineno=")
    public RubyFixnum lineno_set(ThreadContext context, IRubyObject newLineNumber) {
        getOpenFileChecked().setLineNumber(toInt(context, newLineNumber));

        return asFixnum(context, getOpenFileChecked().getLineNumber());
    }

    /** Returns the current sync mode.
     *
     * MRI: rb_io_sync
     *
     * @return the current sync mode.
     */
    @JRubyMethod
    public RubyBoolean sync(ThreadContext context) {
        OpenFile fptr;

        RubyIO io = GetWriteIO();
        fptr = io.getOpenFileChecked();
        fptr.lock();
        try {
            return (fptr.getMode() & OpenFile.SYNC) != 0 ? context.tru : context.fals;
        } finally {
            fptr.unlock();
        }
    }

    /**
     * <p>Return the process id (pid) of the process this IO object
     * spawned.  If no process exists (popen was not called), then
     * nil is returned.  This is not how it appears to be defined
     * but ruby 1.8 works this way.</p>
     *
     * @return the pid or nil
     */
    @JRubyMethod
    public IRubyObject pid(ThreadContext context) {
        OpenFile myOpenFile = getOpenFileChecked();

        if (myOpenFile.getProcess() == null) {
            return context.nil;
        }

        // Of course this isn't particularly useful.
        long pid = myOpenFile.getPid();

        return asFixnum(context, pid);
    }

    // rb_io_pos
    @JRubyMethod(name = {"pos", "tell"})
    public RubyFixnum pos(ThreadContext context) {
        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            long pos = fptr.tell(context);
            if (pos == -1 && fptr.errno() != null) throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            pos -= fptr.rbuf.len;
            return asFixnum(context, pos);
        } finally {
            if (locked) fptr.unlock();
        }
    }

    // rb_io_set_pos
    @JRubyMethod(name = "pos=")
    public RubyFixnum pos_set(ThreadContext context, IRubyObject offset) {
        long pos = toLong(context, offset);
        OpenFile fptr = getOpenFileChecked();
        boolean locked = fptr.lock();

        try {
            pos = fptr.seek(context, pos, PosixShim.SEEK_SET);
            if (pos == -1 && fptr.errno() != null) throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
        } finally {
            if (locked) fptr.unlock();
        }

        return asFixnum(context, pos);
    }

    /** Print some objects to the stream.
     *
     */
    @JRubyMethod(rest = true, reads = LASTLINE)
    public IRubyObject print(ThreadContext context, IRubyObject[] args) {
        return print(context, this, args);
    }

    @JRubyMethod(reads = LASTLINE)
    public IRubyObject print(ThreadContext context) {
        return print0(context, this);
    }

    @JRubyMethod
    public IRubyObject print(ThreadContext context, IRubyObject arg0) {
        return print1(context, this, arg0);
    }

    @JRubyMethod
    public IRubyObject print(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return print2(context, this, arg0, arg1);
    }

    @JRubyMethod
    public IRubyObject print(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return print3(context, this, arg0, arg1, arg2);
    }

    /**
     * Print some objects to the stream.
     *
     * MRI: rb_io_print
     */
    public static IRubyObject print(ThreadContext context, IRubyObject out, IRubyObject[] args) {
        switch (args.length) {
            case 0:
                return print0(context, out);
            case 1:
                return print1(context, out, args[0]);
            case 2:
                return print2(context, out, args[0], args[1]);
            case 3:
                return print3(context, out, args[0], args[1], args[2]);
        }

        int argc = args.length;
        IRubyObject outputFS = globalVariables(context).get("$,");

        boolean fieldSeparatorNotNil = !outputFS.isNil();
        if (fieldSeparatorNotNil) warnDeprecated(context, "$, is set to non-nil value");

        for (int i=0; i<argc; i++) {
            if (fieldSeparatorNotNil && i > 0) write(context, out, outputFS);
            write(context, out, args[i]);
        }

        writeRecordSeparator(context, out);

        return context.nil;
    }

    public static IRubyObject print0(ThreadContext context, IRubyObject out) {
        return print1(context, out, context.getLastLine());
    }

    public static IRubyObject print1(ThreadContext context, IRubyObject out, IRubyObject arg1) {
        write(context, out, arg1);

        writeRecordSeparator(context, out);

        return context.nil;
    }

    private static void writeRecordSeparator(ThreadContext context, IRubyObject out) {
        IRubyObject outputRS = globalVariables(context).get("$\\");
        if (!outputRS.isNil()) {
            write(context, out, outputRS);
        }
    }

    public static IRubyObject print2(ThreadContext context, IRubyObject out, IRubyObject arg0, IRubyObject arg1) {
        IRubyObject outputFS = globalVariables(context).get("$,");
        boolean fieldSeparatorNotNil = !outputFS.isNil();
        if (fieldSeparatorNotNil) warnDeprecated(context, "$, is set to non-nil value");

        write(context, out, arg0);
        if (fieldSeparatorNotNil) write(context, out, outputFS);
        write(context, out, arg1);

        writeRecordSeparator(context, out);

        return context.nil;
    }

    public static IRubyObject print3(ThreadContext context, IRubyObject out, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        IRubyObject outputFS = globalVariables(context).get("$,");
        boolean fieldSeparatorNotNil = !outputFS.isNil();
        if (fieldSeparatorNotNil) warnDeprecated(context, "$, is set to non-nil value");

        write(context, out, arg0);
        if (fieldSeparatorNotNil) write(context, out, outputFS);
        write(context, out, arg1);
        if (fieldSeparatorNotNil) write(context, out, outputFS);
        write(context, out, arg2);

        writeRecordSeparator(context, out);

        return context.nil;
    }

    @JRubyMethod(required = 1, rest = true, checkArity = false)
    public IRubyObject printf(ThreadContext context, IRubyObject[] args) {
        return printf(context, this, args);
    }

    public static IRubyObject printf(ThreadContext context, IRubyObject out, IRubyObject[] args) {
        write(context, out, RubyKernel.sprintf(context, out, args));
        return context.nil;
    }

    @JRubyMethod
    public IRubyObject putc(ThreadContext context, IRubyObject ch) {
        IRubyObject str = ch instanceof RubyString chStr ?
                chStr.substr(context, 0, 1) :
                newSharedString(context, RubyInteger.singleCharByteList(toByte(context, ch)));

        sites(context).write.call(context, this, this, str);

        return ch;
    }

    public static IRubyObject putc(ThreadContext context, IRubyObject maybeIO, IRubyObject object) {
        if (maybeIO instanceof RubyIO io) {
            io.putc(context, object);
        } else {
            IRubyObject str = newSharedString(context, RubyInteger.singleCharByteList(toByte(context, object)));
            sites(context).write.call(context, maybeIO, maybeIO, str);
        }

        return object;
    }

    public RubyFixnum seek(ThreadContext context, IRubyObject[] args) {
        if (args.length > 1) {
            return seek(context, args[0], args[1]);
        }
        return seek(context, args[0]);
    }

    @JRubyMethod
    public RubyFixnum seek(ThreadContext context, IRubyObject off) {
        return asFixnum(context, doSeek(context, toLong(context, off), PosixShim.SEEK_SET));
    }

    @JRubyMethod
    public RubyFixnum seek(ThreadContext context, IRubyObject off, IRubyObject whence) {
        return asFixnum(context, doSeek(context, toLong(context, off), interpretSeekWhence(context, whence)));
    }

    // rb_io_seek
    private long doSeek(ThreadContext context, long pos, int whence) {
        OpenFile fptr;

        fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            pos = fptr.seek(context, pos, whence);
            if (pos < 0 && fptr.errno() != null) {
                throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return 0;
    }

    // This was a getOpt with one mandatory arg, but it did not work
    // so I am parsing it for now.
    @JRubyMethod(required = 1, optional = 1, checkArity = false)
    public RubyFixnum sysseek(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 2);
        IRubyObject offset = context.nil;
        int whence = PosixShim.SEEK_SET;

        switch (argc) {
            case 2:
                IRubyObject ptrname = args[1];
                whence = interpretSeekWhence(context, ptrname);
            case 1:
                offset = args[0];
        }
        long pos = toLong(context, offset);
        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            if ((fptr.isReadable()) &&
                    (fptr.READ_DATA_BUFFERED() || fptr.READ_CHAR_PENDING())) {
                throw context.runtime.newIOError("sysseek for buffered IO");
            }
            if (fptr.isWritable() && fptr.wbuf.len != 0) {
                warn(context, "sysseek for buffered IO");
            }
            fptr.errno(null);
            pos = fptr.posix.lseek(fptr.fd(), pos, whence);
            if (pos == -1 && fptr.errno() != null) throw context.runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
        } finally {
            if (locked) fptr.unlock();
        }

        return asFixnum(context, pos);
    }

    private static int interpretSeekWhence(ThreadContext context, IRubyObject whence) {
        if (whence instanceof RubySymbol sym) {
            switch(sym.idString()) {
                case "SET" -> { return PosixShim.SEEK_SET; }
                case "CUR" -> { return PosixShim.SEEK_CUR; }
                case "END" -> { return PosixShim.SEEK_END; }
            }
        }
        return toInt(context, whence);
    }

    // rb_io_rewind
    @JRubyMethod
    public RubyFixnum rewind(ThreadContext context) {
        Ruby runtime = context.runtime;
        OpenFile fptr;

        fptr = getOpenFileChecked();
        boolean locked = fptr.lock();
        try {
            if (fptr.seek(context, 0L, 0) == -1 && fptr.errno() != null) {
                throw runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            }

            if (RubyArgsFile.ArgsFileData.getArgsFileData(runtime).isCurrentFile(this)) {
                runtime.setCurrentLine(runtime.getCurrentLine() - fptr.getLineNumber());
            }
            fptr.setLineNumber(0);
            if (fptr.readconv != null) {
                fptr.clearReadConversion();
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return RubyFixnum.zero(runtime);
    }

    // rb_io_fsync
    @JRubyMethod
    public RubyFixnum fsync(ThreadContext context) {
        RubyIO io = GetWriteIO();
        OpenFile fptr = io.getOpenFileChecked();

        if (fptr.io_fflush(context) < 0) throw context.runtime.newSystemCallError("");

        if (!Platform.IS_WINDOWS) { /* already called in io_fflush() */
            try {
                if (fptr.fileChannel() != null) fptr.fileChannel().force(true);
                if (fptr.fd().chNative != null) {
                    var posix = context.runtime.getPosix();
                    if (posix.fsync(fptr.fd().chNative.getFD()) < 0) throw context.runtime.newErrnoFromInt(posix.errno());
                }
            } catch (IOException ioe) {
                throw context.runtime.newIOErrorFromException(ioe);
            }
        }

        return asFixnum(context, 0);
    }

    /**
     * @param sync
     * @return
     * @deprecated Use {@link RubyIO#sync_set(ThreadContext, IRubyObject)} instead.
     */
    @Deprecated(since = "10.0")
    public IRubyObject sync_set(IRubyObject sync) {
        return sync_set(getCurrentContext(), sync);
    }

    /** Sets the current sync mode.
     *
     * MRI: rb_io_set_sync
     *
     * @param sync The new sync mode.
     */
    @JRubyMethod(name = "sync=")
    public IRubyObject sync_set(ThreadContext context, IRubyObject sync) {
        setSync(sync.isTrue());

        return sync;
    }

    public void setSync(boolean sync) {
        RubyIO io = GetWriteIO();
        OpenFile fptr = io.getOpenFileChecked();
        fptr.setSync(sync);
    }

    public boolean getSync() {
        RubyIO io = GetWriteIO();
        OpenFile fptr = io.getOpenFileChecked();
        return fptr.isSync();
    }

    // rb_io_eof
    @JRubyMethod(name = {"eof?", "eof"})
    public RubyBoolean eof_p(ThreadContext context) {
        OpenFile fptr;

        fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkCharReadable(context);

            if (fptr.READ_CHAR_PENDING()) return context.fals;
            if (fptr.READ_DATA_PENDING()) return context.fals;
            fptr.READ_CHECK(context);
            //        #if defined(RUBY_TEST_CRLF_ENVIRONMENT) || defined(_WIN32)
            //        if (!NEED_READCONV(fptr) && NEED_NEWLINE_DECORATOR_ON_READ(fptr)) {
            //            return eof(fptr->fd) ? Qtrue : Qfalse;
            //        }
            //        #endif
            if (fptr.fillbuf(context) < 0) {
                return context.tru;
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return context.fals;
    }

    @JRubyMethod(name = {"tty?", "isatty"})
    public RubyBoolean tty_p(ThreadContext context) {
        Ruby runtime = context.runtime;
        POSIX posix = runtime.getPosix();
        OpenFile fptr;

        fptr = getOpenFileChecked();

        fptr.lock();
        try {
            if (posix.isNative() && fptr.fd().realFileno != -1) {
                return posix.libc().isatty(fptr.getFileno()) == 0 ? runtime.getFalse() : runtime.getTrue();
            } else if (fptr.isStdio()) {
                // This is a bit of a hack for platforms where we can't do native stdio
                return runtime.getTrue();
            }
        } finally {
            fptr.unlock();
        }

        return runtime.getFalse();
    }

    // MRI: rb_io_init_copy
    @JRubyMethod(visibility = Visibility.PRIVATE)
    public IRubyObject initialize_copy(ThreadContext context, IRubyObject _io){
        RubyIO dest = this;
        RubyIO io = TypeConverter.ioGetIO(context.runtime, _io);
        if (!OBJ_INIT_COPY(dest, io)) return dest;

        OpenFile orig = io.getOpenFileChecked();
        OpenFile fptr = dest.MakeOpenFile();

        // orig is the visible one here but we lock both anyway
        boolean locked1 = orig.lock();
        boolean locked2 = fptr.lock();
        try {
            io.flush(context);

            /* copy rb_io_t structure */
            fptr.setMode(orig.getMode() & ~OpenFile.PREP);
            fptr.encs = orig.encs;
            fptr.setProcess(orig.getProcess());
            fptr.setLineNumber(orig.getLineNumber());
            if (orig.getPath() != null) fptr.setPath(orig.getPath());
            fptr.setFinalizer(orig.getFinalizer());
            // TODO: not using pipe_finalize yet
            //        #if defined (__CYGWIN__) || !defined(HAVE_FORK)
            //        if (fptr.finalize == pipe_finalize)
            //            pipe_add_fptr(fptr);
            //        #endif

            ChannelFD fd = orig.fd().dup();
            fptr.setFD(fd);
            long pos = orig.tell(context);
            if (pos == -1)
                fptr.seek(context, pos, PosixShim.SEEK_SET);
        } finally {
            if (locked2) fptr.unlock();
            if (locked1) orig.unlock();
        }

        if (fptr.isBinmode()) {
            dest.setBinmode();
        }

        RubyIO write_io = io.GetWriteIO();
        if (io != write_io) {
            write_io = (RubyIO)write_io.dup();
            fptr.tiedIOForWriting = write_io;
            dest.getInstanceVariables().setInstanceVariable("@tied_io_for_writing", write_io);
        }

        return dest;
    }

    @JRubyMethod(name = "closed?")
    public RubyBoolean closed_p(ThreadContext context) {
        return asBoolean(context, isClosed());
    }

    /**
     * Is this IO closed
     *
     * MRI: rb_io_closed
     *
     * @return true if closed
     */
    public boolean isClosed() {
        OpenFile fptr;
        RubyIO write_io;
        OpenFile write_fptr;

        write_io = GetWriteIO();
        if (this != write_io) {
            write_fptr = write_io.openFile;
            if (write_fptr != null && write_fptr.fd() != null) {
                return false;
            }
        }

        fptr = openFile;
        checkInitialized();
        return fptr.fd() == null;
    }

    /**
     * <p>Closes all open resources for the IO.  It also removes
     * it from our magical all open file descriptor pool.</p>
     *
     * @return The IO. Returns nil if the IO was already closed.
     *
     * MRI: rb_io_close_m
     */
    @JRubyMethod
    public IRubyObject close(final ThreadContext context) {
        if (isClosed()) return context.nil;
        return rbIoClose(context);
    }

    public final void close() { close(getRuntime().getCurrentContext()); }

    // io_close
    protected static IRubyObject ioClose(ThreadContext context, IRubyObject io) {
        IOSites sites = sites(context);
        IRubyObject closed = io.checkCallMethod(context, sites.closed_checked);
        if (closed != null && closed.isTrue()) return io;
        IRubyObject oldExc = context.getErrorInfo(); // Save $!
        try {
            closed = io.checkCallMethod(context, sites.close_checked);
            return asBoolean(context, closed != null && closed.isTrue());
        } catch (RaiseException re) {
            if (re.getMessage().contains(CLOSED_STREAM_MSG)) {
                // ignore
                context.setErrorInfo(oldExc); // Restore $!
                return context.nil;
            } else {
                throw re;
            }
        }
    }

    // rb_io_close
    protected IRubyObject rbIoClose(ThreadContext context) {
        OpenFile fptr;
        RubyIO write_io;
        OpenFile write_fptr;

        write_io = GetWriteIO();
        if (this != write_io) {
            write_fptr = write_io.openFile;

            boolean locked = write_fptr.lock();
            try {
                if (write_fptr != null && write_fptr.fd() != null) {
                    write_fptr.cleanup(context.runtime, true);
                }
            } finally {
                if (locked) write_fptr.unlock();
            }
        }

        fptr = openFile;

        boolean locked = fptr.lock();
        try {
            if (fptr == null) return context.nil;
            if (fptr.fd() == null) return context.nil;
            final Ruby runtime = context.runtime;

            fptr.finalizeFlush(context, false);

            // interrupt waiting threads
            fptr.interruptBlockingThreads(context);
            try {
                fptr.unlock();
                fptr.waitForBlockingThreads(context);
            } finally {
                fptr.lock();
            }

            fptr.cleanup(runtime, false);

            if (fptr.getProcess() != null) {
                context.setLastExitStatus(context.nil);

                if (runtime.getPosix().isNative() && fptr.getProcess() instanceof POSIXProcess) {
                    // We do not need to nuke native-launched child process, since we now have full control
                    // over child process pipes.
                    IRubyObject processResult = RubyProcess.RubyStatus.newProcessStatus(runtime, ((POSIXProcess) fptr.getProcess()).status(), fptr.getPid());
                    context.setLastExitStatus(processResult);
                } else {
                    // If this is not a popen3/popen4 stream and it has a process, attempt to shut down that process
                    if (!popenSpecial) {
                        obliterateProcess(fptr.getProcess());
                        // RubyStatus uses real native status now, so we unshift Java's shifted exit status
                        IRubyObject processResult = RubyProcess.RubyStatus.newProcessStatus(runtime, fptr.getProcess().exitValue() << 8, fptr.getPid());
                        context.setLastExitStatus(processResult);
                    }
                }
                fptr.setProcess(null);
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return context.nil;
    }

    // MRI: rb_io_close_write
    @JRubyMethod
    public IRubyObject close_write(ThreadContext context) {
        Ruby runtime = context.runtime;
        OpenFile fptr;
        RubyIO write_io;

        write_io = GetWriteIO();
        fptr = write_io.getOpenFileInitialized();
        if (!fptr.isOpen()) return context.nil;

        boolean locked = fptr.lock();
        try {
            if (fptr.socketChannel() != null) {
                try {
                    fptr.socketChannel().shutdownOutput();
                } catch (IOException ioe) {
                    throw runtime.newErrnoFromErrno(Helpers.errnoFromException(ioe), fptr.getPath());
                }
                fptr.setMode(fptr.getMode() & ~OpenFile.WRITABLE);
                if (!fptr.isReadable()) return write_io.rbIoClose(context);
                return context.nil;
            }

            if (fptr.isReadable() && !fptr.isDuplex()) {
                throw runtime.newIOError("closing non-duplex IO for writing");
            }
        } finally {
            if (locked) fptr.unlock();
        }


        if (this != write_io) {
            fptr = getOpenFileInitialized();

            locked = fptr.lock();
            try {
                fptr.tiedIOForWriting = null;
            } finally {
                if (locked) fptr.unlock();
            }
        }

        write_io.rbIoClose(context);
        return context.nil;
    }

    @JRubyMethod
    public IRubyObject close_read(ThreadContext context) {
        Ruby runtime = context.runtime;
        OpenFile fptr;
        RubyIO write_io;

        fptr = getOpenFileInitialized();
        if (!fptr.isOpen()) return context.nil;

        boolean locked = fptr.lock();
        try {
            if (fptr.socketChannel() != null) {
                try {
                    fptr.socketChannel().socket().shutdownInput();
                } catch (IOException ioe) {
                    throw runtime.newErrnoFromErrno(Helpers.errnoFromException(ioe), fptr.getPath());
                }
                fptr.setMode(fptr.getMode() & ~OpenFile.READABLE);
                if (!fptr.isWritable()) return rbIoClose(context);
                return context.nil;
            }

            write_io = GetWriteIO();
            if (this != write_io) {
                OpenFile wfptr;
                wfptr = write_io.getOpenFileInitialized();

                boolean locked2 = wfptr.lock();
                try {
                    wfptr.setProcess(fptr.getProcess());
                    wfptr.setPid(fptr.getPid());
                    fptr.setProcess(null);
                    fptr.setPid(-1);
                    this.openFile = wfptr;
                    /* bind to write_io temporarily to get rid of memory/fd leak */
                    fptr.tiedIOForWriting = null;
                    write_io.openFile = fptr;
                    fptr.cleanup(runtime, false);
                    /* should not finalize fptr because another thread may be reading it */
                    return context.nil;
                } finally {
                    if (locked2) wfptr.unlock();
                }
            }

            if (fptr.isWritable() && !fptr.isDuplex()) {
                throw runtime.newIOError("closing non-duplex IO for reading");
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return rbIoClose(context);
    }

    public static final int FD_CLOEXEC = 1;

    @JRubyMethod(name = "close_on_exec=")
    public IRubyObject close_on_exec_set(ThreadContext context, IRubyObject arg) {
        Ruby runtime = context.runtime;
        POSIX posix = runtime.getPosix();
        OpenFile fptr = getOpenFileChecked();
        RubyIO write_io;
        int fd = -1;

        if (fptr == null || (fd = fptr.fd().realFileno) == -1
                || !posix.isNative() || Platform.IS_WINDOWS ) {
            runtime.getWarnings().warningDeprecated("close_on_exec may not be implemented on this platform for this stream type: " + fptr.fd().ch.getClass().getSimpleName());
            return context.nil;
        }

        int flag = arg.isTrue() ? FD_CLOEXEC : 0;
        int ret;

        write_io = GetWriteIO();
        if (this != write_io) {
            fptr = write_io.getOpenFileChecked();
            if (fptr != null && 0 <= (fd = fptr.fd().realFileno)) {
                if ((ret = posix.fcntl(fd, Fcntl.F_GETFD)) == -1) return API.sysFailWithPath(context, fptr.getPath());
                if ((ret & FD_CLOEXEC) != flag) {
                    ret = (ret & ~FD_CLOEXEC) | flag;
                    ret = posix.fcntlInt(fd, Fcntl.F_SETFD, ret);
                    if (ret == -1) API.sysFailWithPath(context, fptr.getPath());
                }
            }

        }

        fptr = getOpenFileChecked();
        if (fptr != null && 0 <= (fd = fptr.fd().realFileno)) {
            if ((ret = posix.fcntl(fd, Fcntl.F_GETFD)) == -1) API.sysFailWithPath(context, fptr.getPath());
            if ((ret & FD_CLOEXEC) != flag) {
                ret = (ret & ~FD_CLOEXEC) | flag;
                ret = posix.fcntlInt(fd, Fcntl.F_SETFD, ret);
                if (ret == -1) API.sysFailWithPath(context, fptr.getPath());
            }
        }

        return context.nil;
    }

    @JRubyMethod(name = {"close_on_exec?", "close_on_exec"})
    public IRubyObject close_on_exec_p(ThreadContext context) {
        Ruby runtime = context.runtime;
        POSIX posix = runtime.getPosix();
        OpenFile fptr = getOpenFileChecked();
        int fd = -1;

        if (fptr == null || (fd = fptr.fd().realFileno) == -1
                || !posix.isNative()) {
            return context.fals;
        }

        RubyIO write_io;
        int ret;

        write_io = GetWriteIO();
        if (this != write_io) {
            fptr = write_io.getOpenFileChecked();
            if (fptr != null && 0 <= (fd = fptr.fd().realFileno)) {
                if ((ret = posix.fcntl(fd, Fcntl.F_GETFD)) == -1) API.sysFailWithPath(context, fptr.getPath());
                if ((ret & FD_CLOEXEC) == 0) return context.fals;
            }
        }

        fptr = getOpenFileChecked();
        if (fptr != null && 0 <= (fd = fptr.fd().realFileno)) {
            if ((ret = posix.fcntl(fd, Fcntl.F_GETFD)) == -1) API.sysFailWithPath(context, fptr.getPath());
            if ((ret & FD_CLOEXEC) == 0) return context.fals;
        }
        return context.tru;
    }

    /** Flushes the IO output stream.
     *
     * MRI: rb_io_flush
     *
     * @return The IO.
     */
    @JRubyMethod
    public RubyIO flush(ThreadContext context) {
        return flushRaw(context, true);
    }

    public void flush() { flush(getRuntime().getCurrentContext()); }

    // rb_io_flush_raw
    protected RubyIO flushRaw(ThreadContext context, boolean sync) {
        OpenFile fptr;

        // not possible here
//        if (!RB_TYPE_P(io, T_FILE)) {
//            return rb_funcall(io, id_flush, 0);
//        }

        RubyIO io = GetWriteIO();
        fptr = io.getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            if ((fptr.getMode() & OpenFile.WRITABLE) != 0) {
                if (fptr.io_fflush(context) < 0)
                    throw context.runtime.newErrnoFromErrno(fptr.errno(), "");
                //            #ifdef _WIN32
                //            if (sync && GetFileType((HANDLE)rb_w32_get_osfhandle(fptr->fd)) == FILE_TYPE_DISK) {
                //                rb_thread_io_blocking_region(nogvl_fsync, fptr, fptr->fd);
                //            }
                //            #endif
            }
            if ((fptr.getMode() & OpenFile.READABLE) != 0) {
                fptr.unread(context);
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return io;
    }

    /** Read a line.
     *
     */

    // rb_io_gets_m
    @JRubyMethod(name = "gets", writes = LASTLINE)
    public IRubyObject gets(ThreadContext context) {
        return Getline.getlineCall(context, GETLINE, this, getReadEncoding(context));
    }

    // rb_io_gets_m
    @JRubyMethod(name = "gets", writes = LASTLINE, keywords = true)
    public IRubyObject gets(ThreadContext context, IRubyObject arg) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        return Getline.getlineCall(context, GETLINE, this, getReadEncoding(context), arg, keywords);
    }

    // rb_io_gets_m
    @JRubyMethod(name = "gets", writes = LASTLINE, keywords = true)
    public IRubyObject gets(ThreadContext context, IRubyObject rs, IRubyObject limit_arg) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        return Getline.getlineCall(context, GETLINE, this, getReadEncoding(context), rs, limit_arg, keywords);
    }

    // rb_io_gets_m
    @JRubyMethod(name = "gets", writes = LASTLINE, keywords = true)
    public IRubyObject gets(ThreadContext context, IRubyObject rs, IRubyObject limit_arg, IRubyObject opt) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        return Getline.getlineCall(context, GETLINE, this, getReadEncoding(context), rs, limit_arg, opt, keywords);
    }

    private static final Getline.Callback<RubyIO, IRubyObject> GETLINE = new Getline.Callback<RubyIO, IRubyObject>() {
        @Override
        public IRubyObject getline(ThreadContext context, RubyIO self, IRubyObject rs, int limit, boolean chomp, Block block) {
            IRubyObject result = self.getlineImpl(context, rs, limit, chomp);

            if (result != context.nil) context.setLastLine(result);

            return result;
        }
    };

    private static final Getline.Callback<RubyIO, RubyIO> GETLINE_YIELD = new Getline.Callback<RubyIO, RubyIO>() {
        @Override
        public RubyIO getline(ThreadContext context, RubyIO self, IRubyObject rs, int limit, boolean chomp, Block block) {
            if (limit == 0) {
                throw argumentError(context, "invalid limit: 0 for foreach");
            }

            IRubyObject line;
            while ((line = self.getlineImpl(context, rs, limit, chomp)) != context.nil) {
                block.yieldSpecific(context, line);
            }

            return self;
        }
    };

    private static final Getline.Callback<RubyIO, RubyArray> GETLINE_ARY = new Getline.Callback<RubyIO, RubyArray>() {
        @Override
        public RubyArray getline(ThreadContext context, RubyIO self, IRubyObject rs, int limit, boolean chomp, Block block) {
            
            var ary = newArray(context);
            IRubyObject line;

            while ((line = self.getlineImpl(context, rs, limit, chomp)) != context.nil) {
                ary.append(context, line);
            }

            return ary;
        }
    };

    public boolean getBlocking() {
        return openFile.isBlocking();
    }

    public void setBlocking(boolean blocking) {
        openFile.setBlocking(getRuntime(), blocking);
    }

    @JRubyMethod(name = "fcntl")
    public IRubyObject fcntl(ThreadContext context, IRubyObject cmd) {
        return ctl(context, cmd, null);
    }

    @JRubyMethod(name = "fcntl")
    public IRubyObject fcntl(ThreadContext context, IRubyObject cmd, IRubyObject arg) {
        return ctl(context, cmd, arg);
    }

    @JRubyMethod(name = "ioctl", required = 1, optional = 1, checkArity = false)
    public IRubyObject ioctl(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 2);

        IRubyObject cmd = args[0];
        IRubyObject arg;

        if (argc == 2) {
            arg = args[1];
        } else {
            arg = context.nil;
        }

        return ctl(context, cmd, arg);
    }

    private IRubyObject ctl(ThreadContext context, IRubyObject cmd, IRubyObject arg) {
        Ruby runtime = context.runtime;
        long realCmd = toLong(context, cmd);
        long nArg = 0;

        if (realCmd == Fcntl.F_GETFL.intValue()) {
            OpenFile myOpenFile = getOpenFileChecked();
            return asFixnum(context, OpenFile.ioFmodeOflags(myOpenFile.getMode()));
        }

        // FIXME: Arg may also be true, false, and nil and still be valid.  Strangely enough,
        // protocol conversion is not happening in Ruby on this arg?
        if (arg == null || arg.isNil() || arg == context.fals) {
            nArg = 0;
        } else if (arg instanceof RubyFixnum fixnum) {
            nArg = fixnum.getValue();
        } else if (arg == context.tru) {
            nArg = 1;
        } else {
            throw runtime.newNotImplementedError("JRuby does not support string for second fcntl/ioctl argument yet");
        }

        OpenFile fptr = getOpenFileChecked();

        // This currently only supports setting two flags:
        // FD_CLOEXEC on platforms where it is supported, and
        // O_NONBLOCK when the stream can be set to non-blocking.

        // FIXME: F_SETFL and F_SETFD are treated as the same thing here.  For the case of dup(fd) we
        //   should actually have F_SETFL only affect one (it is unclear how well we do, but this TODO
        //   is here to at least document that we might need to do more work here.  Mostly SETFL is
        //   for mode changes which should persist across fork() boundaries.  Since JVM has no fork
        //   this is not a problem for us.
        if (realCmd == FcntlLibrary.FD_CLOEXEC) {
            close_on_exec_set(context, context.tru);
        } else if (realCmd == Fcntl.F_SETFD.intValue()) {
            if (arg != null && (nArg & FcntlLibrary.FD_CLOEXEC) == FcntlLibrary.FD_CLOEXEC) {
                close_on_exec_set(context, arg);
            } else {
                throw runtime.newNotImplementedError("F_SETFD only supports FD_CLOEXEC");
            }
        } else if (realCmd == Fcntl.F_GETFD.intValue()) {
            return asFixnum(context, close_on_exec_p(context).isTrue() ? FD_CLOEXEC : 0);
        } else if (realCmd == Fcntl.F_SETFL.intValue()) {
            if ((nArg & OpenFlags.O_NONBLOCK.intValue()) != 0) {
                fptr.setBlocking(runtime, false);
            } else {
                fptr.setBlocking(runtime, true);
            }

            if ((nArg & OpenFlags.O_CLOEXEC.intValue()) != 0) {
                close_on_exec_set(context, context.tru);
            } else {
                close_on_exec_set(context, context.fals);
            }
        } else if (realCmd == Fcntl.F_GETFL.intValue()) {
            return asFixnum(context,
                    (fptr.isBlocking() ? 0 : OpenFlags.O_NONBLOCK.intValue()) |
                            (close_on_exec_p(context).isTrue() ? FD_CLOEXEC : 0));
        } else {
            throw runtime.newNotImplementedError("JRuby only supports F_SETFL and F_GETFL with NONBLOCK for fcntl/ioctl");
        }

        return asFixnum(context, 0);
    }

    @JRubyMethod(name = "puts")
    public IRubyObject puts(ThreadContext context) {
        return puts0(context, this);
    }

    @JRubyMethod(name = "puts")
    public IRubyObject puts(ThreadContext context, IRubyObject arg0) {
        return puts1(context, this, arg0);
    }

    @JRubyMethod(name = "puts")
    public IRubyObject puts(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        return puts2(context, this, arg0, arg1);
    }

    @JRubyMethod(name = "puts")
    public IRubyObject puts(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        return puts3(context, this, arg0, arg1, arg2);
    }

    @JRubyMethod(name = "puts", rest = true)
    public IRubyObject puts(ThreadContext context, IRubyObject[] args) {
        return puts(context, this, args);
    }

    public static IRubyObject puts0(ThreadContext context, IRubyObject maybeIO) {
        return writeSeparator(context, maybeIO);
    }

    public static IRubyObject puts1(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0) {
        Ruby runtime = context.runtime;
        assert globalVariables(context).getDefaultSeparator() instanceof RubyString;
        RubyString separator = (RubyString) globalVariables(context).getDefaultSeparator();

        putsSingle(context, runtime, maybeIO, arg0, separator);

        return context.nil;
    }

    public static IRubyObject puts2(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0, IRubyObject arg1) {
        Ruby runtime = context.runtime;
        assert globalVariables(context).getDefaultSeparator() instanceof RubyString;
        RubyString separator = (RubyString) globalVariables(context).getDefaultSeparator();

        putsSingle(context, runtime, maybeIO, arg0, separator);
        putsSingle(context, runtime, maybeIO, arg1, separator);

        return context.nil;
    }

    public static IRubyObject puts3(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        Ruby runtime = context.runtime;
        assert globalVariables(context).getDefaultSeparator() instanceof RubyString;
        RubyString separator = (RubyString) globalVariables(context).getDefaultSeparator();

        putsSingle(context, runtime, maybeIO, arg0, separator);
        putsSingle(context, runtime, maybeIO, arg1, separator);
        putsSingle(context, runtime, maybeIO, arg2, separator);

        return context.nil;
    }

    public static IRubyObject puts(ThreadContext context, IRubyObject maybeIO, IRubyObject... args) {
        if (args.length == 0) {
            return writeSeparator(context, maybeIO);
        }

        return putsArray(context, maybeIO, args);
    }

    private static IRubyObject writeSeparator(ThreadContext context, IRubyObject maybeIO) {
        Ruby runtime = context.runtime;
        assert globalVariables(context).getDefaultSeparator() instanceof RubyString;
        RubyString separator = (RubyString) globalVariables(context).getDefaultSeparator();

        write(context, maybeIO, separator);
        return context.nil;
    }

    private static IRubyObject putsArray(ThreadContext context, IRubyObject maybeIO, IRubyObject[] args) {
        Ruby runtime = context.runtime;
        assert globalVariables(context).getDefaultSeparator() instanceof RubyString;
        RubyString separator = (RubyString) globalVariables(context).getDefaultSeparator();

        for (int i = 0; i < args.length; i++) {
            putsSingle(context, runtime, maybeIO, args[i], separator);
        }

        return context.nil;
    }

    private static final ByteList RECURSIVE_BYTELIST = ByteList.create("[...]");

    private static void putsSingle(ThreadContext context, Ruby runtime, IRubyObject maybeIO, IRubyObject arg, RubyString separator) {
        ByteList line;
        RubyString string;

        if (arg.isNil()) {
            line = ByteList.EMPTY_BYTELIST;
            string = null;
        } else if (runtime.isInspecting(arg)) {
            line = RECURSIVE_BYTELIST;
            string = null;
        } else if (arg instanceof RubyArray) {
            inspectPuts(context, maybeIO, (RubyArray) arg);
            return;
        } else {
            string = arg.asString();
            line = string.getByteList();
        }

        boolean writeSeparator = line.length() == 0 || !line.endsWith(separator.getByteList());

        if (string != null) {
            if (writeSeparator) {
                write(context, maybeIO, string, separator);
            } else {
                write(context, maybeIO, string);
            }
        } else {
            if (writeSeparator) {
                write(context, maybeIO, line, separator);
            } else {
                write(context, maybeIO, line);
            }
        }
    }

    private static IRubyObject inspectPuts(ThreadContext context, IRubyObject maybeIO, RubyArray array) {
        try {
            context.runtime.registerInspecting(array);
            return putsArray(context, maybeIO, array.toJavaArrayMaybeUnsafe());
        } finally {
            context.runtime.unregisterInspecting(array);
        }
    }

    protected static IRubyObject write(ThreadContext context, IRubyObject maybeIO, ByteList byteList) {
        return write(context, maybeIO, newSharedString(context, byteList));
    }

    // MRI: rb_io_writev with string as ByteList
    protected static IRubyObject write(ThreadContext context, IRubyObject maybeIO, ByteList byteList, IRubyObject sep) {
        return write(context, maybeIO, newSharedString(context, byteList), sep);
    }

    public static IRubyObject write(ThreadContext context, IRubyObject maybeIO, IRubyObject str) {
        return sites(context).write.call(context, maybeIO, maybeIO, str);
    }

    // MRI: rb_io_writev with string as IRubyObject
    public static IRubyObject write(ThreadContext context, IRubyObject maybeIO, IRubyObject arg0, IRubyObject arg1) {
        CachingCallSite write = sites(context).write;

        // In MRI this is used for all multi-arg puts calls to write. Here, we just do it for two
        if (write.retrieveCache(maybeIO.getMetaClass()).method.getSignature().isOneArgument()) {
            Ruby runtime = context.runtime;
            if (runtime.isVerbose() && maybeIO != globalVariables(context).get("$stderr")) {
                warnWrite(context, maybeIO);
            }
            write.call(context, maybeIO, maybeIO, arg0);
            write.call(context, maybeIO, maybeIO, arg1);
            return arg0;         /* unused right now */
        }
        return write.call(context, maybeIO, maybeIO, arg0, arg1);
    }

    // MRI: rb_io_writev
    public static IRubyObject writev(ThreadContext context, IRubyObject io, IRubyObject[] args) {
        switch (args.length) {
            case 0:
                return writev(context, io);
            case 1:
                return writev(context, io, args[0]);
            case 2:
                return writev(context, io, args[0], args[1]);
            case 3:
                return writev(context, io, args[0], args[1], args[2]);
        }

        CachingCallSite write = sites(context).write;

        if (args.length > 1 && write.retrieveCache(io).method.getSignature() == Signature.ONE_ARGUMENT) {
            warnWrite(context, io);

            for (IRubyObject arg : args) {
                write.call(context, io, io, arg);
            }

            return context.nil;
        }

        return write.call(context, io, io, args);
    }

    // MRI: rb_io_writev
    public static IRubyObject writev(ThreadContext context, IRubyObject io) {
        return sites(context).write.call(context, io, io);
    }

    // MRI: rb_io_writev
    public static IRubyObject writev(ThreadContext context, IRubyObject io, IRubyObject arg0) {
        return sites(context).write.call(context, io, io, arg0);
    }

    // MRI: rb_io_writev
    public static IRubyObject writev(ThreadContext context, IRubyObject io, IRubyObject arg0, IRubyObject arg1) {
        CachingCallSite write = sites(context).write;

        if (write.retrieveCache(io).method.getSignature() == Signature.ONE_ARGUMENT) {
            warnWrite(context, io);

            write.call(context, io, io, arg0);
            write.call(context, io, io, arg1);

            return context.nil;
        }

        return write.call(context, io, io, arg0, arg1);
    }

    // MRI: rb_io_writev
    public static IRubyObject writev(ThreadContext context, IRubyObject io, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        CachingCallSite write = sites(context).write;

        if (write.retrieveCache(io).method.getSignature() == Signature.ONE_ARGUMENT) {
            warnWrite(context, io);

            write.call(context, io, io, arg0);
            write.call(context, io, io, arg1);
            write.call(context, io, io, arg2);

            return context.nil;
        }

        return write.call(context, io, io, arg0, arg1, arg2);
    }

    private static void warnWrite(ThreadContext context, IRubyObject maybeIO) {
        // CRuby checks ractor stderr here
        if (maybeIO != globalVariables(context).get("$stderr") && context.runtime.isVerbose()) {
            IRubyObject klass = maybeIO.getMetaClass();
            char sep;
            if (((RubyClass) klass).isSingleton()) {
                klass = maybeIO;
                sep = '.';
            } else {
                sep = '#';
            }
            warningDeprecated(context, klass.toString() + sep + "write is outdated interface which accepts just one argument");
        }
    }

    @JRubyMethod
    public IRubyObject inspect(ThreadContext context) {
        final OpenFile openFile = this.openFile;
        if (openFile == null) return super.inspect(context);

        String className = getMetaClass().getRealClass().getName(context);
        String path = openFile.getPath();
        String status = "";

        if (path == null || path == "") {
            if (openFile.fd() == null) {
                path = "";
                status = "(closed)";
            } else {
                path = "fd " + openFile.fd().bestFileno();
            }
        } else if (!openFile.isOpen()) {
            status = " (closed)";
        }

        return newString(context, "#<" + className + ':' + path + status + '>');
    }

    /** Read a line.
     *
     */
    @JRubyMethod(name = "readline", writes = LASTLINE)
    public IRubyObject readline(ThreadContext context) {
        IRubyObject line = gets(context);

        if (line == context.nil) throw context.runtime.newEOFError();

        return line;
    }

    @JRubyMethod(name = "readline", writes = LASTLINE, keywords = true)
    public IRubyObject readline(ThreadContext context, IRubyObject separator) {
        IRubyObject line = gets(context, separator);

        if (line == context.nil) throw context.runtime.newEOFError();

        return line;
    }

    @JRubyMethod(name = "readline", writes = LASTLINE, keywords = true)
    public IRubyObject readline(ThreadContext context, IRubyObject separator, IRubyObject limit) {
        IRubyObject line = gets(context, separator, limit);

        if (line == context.nil) throw context.runtime.newEOFError();

        return line;
    }

    @JRubyMethod(name = "readline", writes = LASTLINE, keywords = true)
    public IRubyObject readline(ThreadContext context, IRubyObject separator, IRubyObject limit, IRubyObject opts) {
        IRubyObject line = gets(context, separator, limit, opts);

        if (line == context.nil) throw context.runtime.newEOFError();

        return line;
    }

    @Deprecated
    public IRubyObject getc() {
        return getbyte(getCurrentContext());
    }

    /**
     * Read a char. On EOF throw EOFError.
     */ // rb_io_readchar
    @JRubyMethod
    public IRubyObject readchar(ThreadContext context) {
        IRubyObject c = getc(context);

        if (c == context.nil) throw context.runtime.newEOFError();

        return c;
    }

    /**
     * Read a byte. On EOF returns nil.
     */ // rb_io_getbyte
    @JRubyMethod
    public IRubyObject getbyte(ThreadContext context) {
        int c = getByte(context);

        return c == -1 ? context.nil : asFixnum(context, c & 0xff);
    }

    // rb_io_getbyte
    public int getByte(ThreadContext context) {
        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkByteReadable(context);
            fptr.READ_CHECK(context);
            // TODO: tty flushing
            //        if (fptr->fd == 0 && (fptr->mode & FMODE_TTY) && RB_TYPE_P(rb_stdout, T_FILE)) {
            //            rb_io_t *ofp;
            //            GetOpenFile(rb_stdout, ofp);
            //            if (ofp->mode & FMODE_TTY) {
            //                rb_io_flush(rb_stdout);
            //            }
            //        }
            if (fptr.fillbuf(context) < 0) {
                return -1;
            }
            fptr.rbuf.off++;
            fptr.rbuf.len--;
            return fptr.rbuf.ptr[fptr.rbuf.off - 1] & 0xFF;
        } finally {
            if (locked) fptr.unlock();
        }
    }

    // rb_io_readbyte
    @JRubyMethod
    public IRubyObject readbyte(ThreadContext context) {
        IRubyObject c = getbyte(context);

        if (c == context.nil) throw context.runtime.newEOFError();

        return c;
    }

    // rb_io_getc
    @JRubyMethod(name = "getc")
    public IRubyObject getc(ThreadContext context) {
        Ruby runtime = context.runtime;
        Encoding enc;

        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkCharReadable(context);

            enc = fptr.inputEncoding(runtime);
            fptr.READ_CHECK(context);
            return fptr.getc(context, enc);
        } finally {
            if (locked) fptr.unlock();
        }
    }

    // rb_io_ungetbyte
    @JRubyMethod
    public IRubyObject ungetbyte(ThreadContext context, IRubyObject b) {
        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkByteReadable(context);
            if (b.isNil()) return context.nil;
            if (b instanceof RubyInteger bint) {
                byte cc = (byte) ((RubyInteger) bint.op_mod(context, 256)).asInt(context);
                b = RubyString.newStringNoCopy(context.runtime, new byte[]{cc});
            } else {
                b = b.convertToString();
            }
            fptr.ungetbyte(context, b);
        } finally {
            if (locked) fptr.unlock();
        }

        return context.nil;
    }

    // MRI: rb_io_ungetc
    @JRubyMethod
    public IRubyObject ungetc(ThreadContext context, IRubyObject c) {
        Ruby runtime = context.runtime;

        final OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkCharReadable(context);
            if (c instanceof RubyInteger) {
                c = EncodingUtils.encUintChr(context, (int) ((RubyInteger) c).asLong(context), fptr.readEncoding(runtime));
            } else {
                c = c.convertToString();
            }
            if (fptr.needsReadConversion()) {
                fptr.SET_BINARY_MODE();
                final int len = ((RubyString) c).size();
                //            #if SIZEOF_LONG > SIZEOF_INT
                //            if (len > INT_MAX)
                //                rb_raise(rb_eIOError, "ungetc failed");
                //            #endif
                fptr.makeReadConversion(context, len);
                if (fptr.cbuf.capa - fptr.cbuf.len < len)
                    throw runtime.newIOError("ungetc failed");
                // shift cbuf back to 0
                if (fptr.cbuf.off < len) {
                    System.arraycopy(
                            fptr.cbuf.ptr, fptr.cbuf.off,
                            fptr.cbuf.ptr, fptr.cbuf.capa - fptr.cbuf.len, // this should be 0
                            fptr.cbuf.len);
                    fptr.cbuf.off = fptr.cbuf.capa - fptr.cbuf.len; // this should be 0 too
                }
                fptr.cbuf.off -= len;
                fptr.cbuf.len += len;
                ByteList cByteList = ((RubyString) c).getByteList();
                System.arraycopy(cByteList.unsafeBytes(), cByteList.begin(), fptr.cbuf.ptr, fptr.cbuf.off, len);
            } else {
                fptr.NEED_NEWLINE_DECORATOR_ON_READ_CHECK();
                fptr.ungetbyte(context, c);
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return context.nil;
    }

    @JRubyMethod(name = "read_nonblock")
    public IRubyObject read_nonblock(ThreadContext context, IRubyObject arg0) {
        boolean exception = ArgsUtil.extractKeywordArg(context, "exception", arg0) != context.fals;
        return doReadNonblock(context, arg0, exception);
    }

    @JRubyMethod(name = "read_nonblock")
    public IRubyObject read_nonblock(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        boolean exception = ArgsUtil.extractKeywordArg(context, "exception", arg1) != context.fals;
        return doReadNonblock(context, arg0, arg1, exception);
    }

    @JRubyMethod(name = "read_nonblock")
    public IRubyObject read_nonblock(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        boolean exception = ArgsUtil.extractKeywordArg(context, "exception", arg2) != context.fals;
        return doReadNonblock(context, arg0, arg1, arg2, exception);
    }

    // MRI: io_read_nonblock
    public IRubyObject doReadNonblock(ThreadContext context, IRubyObject arg0, boolean exception) {
        IRubyObject ret = getPartial(context, arg0, true, !exception);
        return ret == context.nil ? nonblockEOF(context.runtime, !exception) : ret;
    }

    // MRI: io_read_nonblock
    public IRubyObject doReadNonblock(ThreadContext context, IRubyObject arg0, IRubyObject arg1, boolean exception) {
        IRubyObject ret = getPartial(context, arg0, arg1, true, !exception);
        return ret == context.nil ? nonblockEOF(context.runtime, !exception) : ret;
    }

    // MRI: io_read_nonblock
    public IRubyObject doReadNonblock(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, boolean exception) {
        IRubyObject ret = getPartial(context, arg0, arg1, arg2, true, !exception);
        return ret == context.nil ? nonblockEOF(context.runtime, !exception) : ret;
    }

    // MRI: io_read_nonblock
    public IRubyObject doReadNonblock(ThreadContext context, IRubyObject[] args, boolean exception) {
        IRubyObject ret = getPartial(context, args, true, !exception);
        return ret == context.nil ? nonblockEOF(context.runtime, !exception) : ret;
    }

    // MRI: io_nonblock_eof(VALUE opts)
    static IRubyObject nonblockEOF(final Ruby runtime, final boolean noException) {
        if ( noException ) return runtime.getNil();
        throw runtime.newEOFError();
    }

    @JRubyMethod(name = "readpartial")
    public IRubyObject readpartial(ThreadContext context, IRubyObject arg0) {
        IRubyObject value = getPartial(context, arg0, false, false);

        if (value.isNil()) {
            throw context.runtime.newEOFError();
        }

        return value;
    }

    @JRubyMethod(name = "readpartial")
    public IRubyObject readpartial(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        // ruby bug 11885
        arg1 = arg1.convertToString();

        IRubyObject value = getPartial(context, arg0, arg1, false, false);

        if (value.isNil()) {
            throw context.runtime.newEOFError();
        }

        return value;
    }

    // MRI: io_getpartial
    IRubyObject getPartial(ThreadContext context, boolean nonblock, boolean noException) {
        return getPartialCommon(context, context.runtime, context.nil, context.nil, nonblock, noException);
    }

    IRubyObject getPartial(ThreadContext context, IRubyObject arg0, boolean nonblock, boolean noException) {
        return getPartialCommon(context, context.runtime, arg0, context.nil, nonblock, noException);
    }

    IRubyObject getPartial(ThreadContext context, IRubyObject arg0, IRubyObject arg1, boolean nonblock, boolean noException) {
        Ruby runtime = context.runtime;
        IRubyObject str = TypeConverter.checkHashType(runtime, arg1).isNil() ? arg1 : context.nil;

        return getPartialCommon(context, runtime, arg0, str, nonblock, noException);
    }

    IRubyObject getPartial(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, boolean nonblock, boolean noException) {
        // exists in pre-refactor code but unclear if the convert failure is really the only behavior expected
        arg2.convertToHash();

        return getPartialCommon(context, context.runtime, arg0, arg1, nonblock, noException);
    }

    IRubyObject getPartial(ThreadContext context, IRubyObject[] args, boolean nonblock, boolean noException) {
        switch (args.length) {
            case 3:
                return getPartial(context, args[0], args[1], args[2], nonblock, noException);
            case 2:
                return getPartial(context, args[0], args[1], nonblock, noException);
            case 1:
                return getPartial(context, args[0], nonblock, noException);
            default:
                return getPartial(context, nonblock, noException);
        }
    }

    private IRubyObject getPartialCommon(ThreadContext context, Ruby runtime, IRubyObject length, IRubyObject str, boolean nonblock, boolean noException) {
        final int len = toInt(context, length);
        if (len < 0 ) throw argumentError(context, "negative length " + len + " given");

        str = EncodingUtils.setStrBuf(runtime, str, len);
        OpenFile fptr = getOpenFileChecked();

        final boolean locked = fptr.lock();
        int n;
        try {
            fptr.checkByteReadable(context);

            if ( len == 0 ) {
                ((RubyString) str).clear(context);
                return str;
            }

            if ( !nonblock) fptr.READ_CHECK(context);

            ByteList strByteList = ((RubyString) str).getByteList();
            n = fptr.readBufferedData(strByteList.unsafeBytes(), strByteList.begin(), len);
            if (n <= 0) {
                again:
                while (true) {
                    if (nonblock) {
                        fptr.setNonblock(runtime);
                    }
                    str = EncodingUtils.setStrBuf(runtime, str, len);
                    strByteList = ((RubyString) str).getByteList();
                    //                arg.fd = fptr->fd;
                    //                arg.str_ptr = RSTRING_PTR(str);
                    //                arg.len = len;
                    //                rb_str_locktmp_ensure(str, read_internal_call, (VALUE)&arg);
                    //                n = arg.len;
                    n = OpenFile.readInternal(context, fptr, fptr.fd(), strByteList.unsafeBytes(), strByteList.begin(), len);
                    if (n < 0) {
                        Errno e = fptr.errno();
                        if (!nonblock && fptr.waitReadable(context))
                            continue again;
                        if (nonblock && (e == Errno.EWOULDBLOCK || e == Errno.EAGAIN)) {
                            if (noException) return asSymbol(context, "wait_readable");
                            throw runtime.newErrnoEAGAINReadableError("read would block");
                        }
                        return nonblockEOF(runtime, noException);
                    }
                    break;
                }
            }
        }
        finally {
            if ( locked ) fptr.unlock();
        }

        ((RubyString) str).setReadLength(n);

        return n == 0 ? context.nil : str;
    }

    // MRI: rb_io_sysread
    @JRubyMethod(name = "sysread")
    public IRubyObject sysread(ThreadContext context, IRubyObject _length) {
        return sysreadCommon(context, context.runtime, _length, context.nil);
    }

    // MRI: rb_io_sysread
    @JRubyMethod(name = "sysread")
    public IRubyObject sysread(ThreadContext context, IRubyObject _length, IRubyObject _str) {
        return sysreadCommon(context, context.runtime, _length, _str);
    }

    private RubyString sysreadCommon(ThreadContext context, Ruby runtime, IRubyObject _length, IRubyObject _str) {
        final int length = toInt(context, _length);
        RubyString str = EncodingUtils.setStrBuf(runtime, _str, length);

        if (length == 0) return str;

        final OpenFile fptr = getOpenFileChecked();

        final int n;
        boolean locked = fptr.lock();
        try {
            fptr.checkByteReadable(context);

            if (fptr.READ_DATA_BUFFERED()) {
                throw runtime.newIOError("sysread for buffered IO");
            }

            /*
             * MRI COMMENT:
             * FIXME: removing rb_thread_wait_fd() here changes sysread semantics
             * on non-blocking IOs.  However, it's still currently possible
             * for sysread to raise Errno::EAGAIN if another thread read()s
             * the IO after we return from rb_thread_wait_fd() but before
             * we call read()
             */
            context.getThread().select(fptr.channel(), fptr, SelectionKey.OP_READ);

            fptr.checkClosed();

            ByteList strByteList = str.getByteList();
            n = OpenFile.readInternal(context, fptr, fptr.fd(), strByteList.unsafeBytes(), strByteList.begin(), length);

            if (n == -1) {
                throw runtime.newErrnoFromErrno(fptr.errno(), fptr.getPath());
            }
        }
        finally {
            if (locked) fptr.unlock();
        }

        if (n == 0 && length > 0) throw runtime.newEOFError();

        str.setReadLength(n);
        return str;
    }

    @Deprecated(since = "10.0")
    public IRubyObject read(IRubyObject[] args) {
        return read(getCurrentContext(), args);
    }

    // io_read
    public IRubyObject read(ThreadContext context, IRubyObject[] args) {
        return switch (args.length) {
            case 0 -> read(context);
            case 1 -> read(context, args[0]);
            case 2 -> read(context, args[0], args[1]);
            default -> throw argumentError(context, args.length, 2);
        };
    }

    /**
     * Read all available bytes.
     *
     * Equivalent to io_read with no arguments.
     *
     * @param context the current context
     * @return the output buffer viewing the actual range of bytes read
     */
    @JRubyMethod(name = "read")
    public IRubyObject read(ThreadContext context) {
        return read(context, context.nil, context.nil);
    }

    /**
     * Read length bytes.
     *
     * Equivalent to io_read with a length argument.
     *
     * @param context the current context
     * @param length a numeric value of the count of bytes to read
     * @return the output buffer viewing the actual range of bytes read
     */
    @JRubyMethod(name = "read")
    public IRubyObject read(ThreadContext context, IRubyObject length) {
        return read(context, length, context.nil);
    }

    /**
     * Read into the given buffer (or create a new one) reading length bytes.
     *
     * Equivalent to io_read.
     *
     * @param context the current context
     * @param length a numeric value of the count of bytes to read
     * @param maybeStr a RubyString buffer or a nil to indicate a new buffer should be created
     * @return the output buffer viewing the actual range of bytes read
     */
    @JRubyMethod(name = "read")
    public IRubyObject read(ThreadContext context, IRubyObject length, IRubyObject maybeStr) {
        if (length == context.nil) {
            OpenFile fptr = getOpenFileChecked();

            boolean locked = fptr.lock();
            try {
                fptr.checkCharReadable(context);
                return fptr.readAll(context, fptr.remainSize(), maybeStr);
            } finally {
                if (locked) fptr.unlock();
            }
        }

        int len = toInt(context, length);

        checkLength(context, len);

        RubyString str = EncodingUtils.setStrBuf(context.runtime, maybeStr, len);

        OpenFile fptr = getOpenFileChecked();
        boolean locked = fptr.lock();
        try {
            fptr.checkByteReadable(context);

            if (len == 0) {
                str.setReadLength(0);
                return str;
            }

            ByteList strByteList = str.getByteList();

            len = doRead(context, fptr, strByteList.unsafeBytes(), strByteList.begin(), len);
        } finally {
            if (locked) fptr.unlock();
        }

        str.setReadLength(len);

        if (len == 0) return context.nil;

        return str;
    }

    protected void checkLength(ThreadContext context, int len) {
        if (len < 0) {
            throw argumentError(context, "negative length " + len + " given");
        }
    }

    /**
     * Read into the given buffer starting from start and reading len bytes.
     *
     * Equivalent to io_read with target byte[] buffer already in hand.
     *
     * @param context the current context
     * @param buffer the target buffer
     * @param start start offset in target buffer
     * @param len count of bytes to read
     * @return the number of bytes actually read or -1 for EOF
     */
    public int read(ThreadContext context, byte[] buffer, int start, int len) {
        checkLength(context, len);

        OpenFile fptr = getOpenFileChecked();
        boolean locked = fptr.lock();
        try {
            fptr.checkByteReadable(context);

            len = doRead(context, fptr, buffer, start, len);

            // Java convention
            if (len == 0) return -1;

            return len;
        } finally {
            if (locked) fptr.unlock();
        }
    }

    protected static int doRead(ThreadContext context, OpenFile fptr, byte[] buffer, int start, int len) {
        if (len == 0) return len;

        fptr.READ_CHECK(context);
        //        #if defined(RUBY_TEST_CRLF_ENVIRONMENT) || defined(_WIN32)
        //        previous_mode = set_binary_mode_with_seek_cur(fptr);
        //        #endif

//        #if defined(RUBY_TEST_CRLF_ENVIRONMENT) || defined(_WIN32)
//        if (previous_mode == O_TEXT) {
//            setmode(fptr->fd, O_TEXT);
//        }
//        #endif

        return fptr.fread(context, buffer, start, len);
    }

    @Deprecated
    public IRubyObject readchar() {
        return readchar(getCurrentContext());
    }

    @JRubyMethod
    public IRubyObject stat(ThreadContext context) {
        Ruby runtime = context.runtime;
        OpenFile fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            int realFileno;
            fptr.checkClosed();
            if (runtime.getPosix().isNative() && (realFileno = fptr.fd().realFileno) != -1) {
                return RubyFileStat.newFileStat(runtime, realFileno);
            } else {
                // no real fd, stat the path
                return context.runtime.newFileStat(fptr.getPath(), false);
            }
        } finally {
            if (locked) fptr.unlock();
        }
    }

    /**
     * <p>Invoke a block for each byte.</p>
     *
     * MRI: rb_io_each_byte
     */
    public IRubyObject each_byteInternal(ThreadContext context, Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_byte");

        OpenFile fptr = getOpenFileChecked();
        boolean locked = fptr.lock();

        try {
            do {
                while (fptr.rbuf.len > 0) {
                    byte[] pBytes = fptr.rbuf.ptr;
                    int p = fptr.rbuf.off++;
                    fptr.rbuf.len--;
                    block.yield(context, asFixnum(context, pBytes[p] & 0xFF));
                    fptr.errno(null);
                }
                fptr.checkByteReadable(context);
                fptr.READ_CHECK(context);
            } while (fptr.fillbuf(context) >= 0);
        } finally {
            if (locked) fptr.unlock();
        }
        return this;
    }

    @JRubyMethod
    public IRubyObject each_byte(ThreadContext context, Block block) {
        return block.isGiven() ? each_byteInternal(context, block) : enumeratorize(context.runtime, this, "each_byte");
    }

    @Deprecated(since = "10.0")
    public IRubyObject bytes(ThreadContext context, Block block) {
        context.runtime.getWarnings().warn("IO#bytes is deprecated; use #each_byte instead");
        return each_byte(context, block);
    }

    // rb_io_each_char
    public IRubyObject each_charInternal(ThreadContext context, Block block) {
        Ruby runtime = context.runtime;
        OpenFile fptr;
        Encoding enc;
        IRubyObject c;

        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_char");
        fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkCharReadable(context);

            enc = fptr.inputEncoding(runtime);
            fptr.READ_CHECK(context);
            while (!(c = fptr.getc(context, enc)).isNil()) {
                block.yield(context, c);
            }
        } finally {
            if (locked) fptr.unlock();
        }

        return this;
    }

    @JRubyMethod(name = "each_char")
    public IRubyObject each_char(ThreadContext context, Block block) {
        return each_charInternal(context, block);
    }

    @Deprecated(since = "10.0")
    public IRubyObject chars(ThreadContext context, Block block) {
        context.runtime.getWarnings().warn("IO#chars is deprecated; use #each_char instead");
        return each_charInternal(context, block);
    }

    @Deprecated(since = "10.0")
    public IRubyObject codepoints(ThreadContext context, Block block) {
        context.runtime.getWarnings().warn("IO#codepoints is deprecated; use #each_codepoint instead");
        return eachCodePointCommon(context, block, "each_codepoint");
    }

    @JRubyMethod
    public IRubyObject each_codepoint(ThreadContext context, Block block) {
        return eachCodePointCommon(context, block, "each_codepoint");
    }

    // rb_io_each_codepoint
    private IRubyObject eachCodePointCommon(ThreadContext context, Block block, String methodName) {
        OpenFile fptr;
        Encoding enc;
        int c;
        int r, n;

        if (!block.isGiven()) return enumeratorize(context.runtime, this, methodName);
        fptr = getOpenFileChecked();

        boolean locked = fptr.lock();
        try {
            fptr.checkCharReadable(context);

            fptr.READ_CHECK(context);
            if (fptr.needsReadConversion()) {
                fptr.SET_BINARY_MODE();
                r = 1;		/* no invalid char yet */
                for (;;) {
                    fptr.makeReadConversion(context);
                    for (;;) {
                        if (fptr.cbuf.len != 0) {
                            if (fptr.encs.enc != null)
                                r = StringSupport.preciseLength(fptr.encs.enc, fptr.cbuf.ptr, fptr.cbuf.off, fptr.cbuf.off + fptr.cbuf.len);
                            else
                                r = StringSupport.CONSTRUCT_MBCLEN_CHARFOUND(1);
                            if (!StringSupport.MBCLEN_NEEDMORE_P(r))
                                break;
                            if (fptr.cbuf.len == fptr.cbuf.capa) {
                                throw context.runtime.newIOError("too long character");
                            }
                        }
                        if (fptr.moreChar(context) == OpenFile.MORE_CHAR_FINISHED) {
                            fptr.clearReadConversion();
                            if (!StringSupport.MBCLEN_CHARFOUND_P(r)) {
                                throw argumentError(context, "invalid byte sequence in " + fptr.encs.enc);
                            }
                            return this;
                        }
                    }
                    if (StringSupport.MBCLEN_INVALID_P(r)) {
                        throw argumentError(context, "invalid byte sequence in " + fptr.encs.enc);
                    }
                    n = StringSupport.MBCLEN_CHARFOUND_LEN(r);
                    if (fptr.encs.enc != null) {
                        c = StringSupport.codePoint(context, fptr.encs.enc, fptr.cbuf.ptr, fptr.cbuf.off, fptr.cbuf.off + fptr.cbuf.len);
                    }
                    else {
                        c = fptr.cbuf.ptr[fptr.cbuf.off] & 0xFF;
                    }
                    fptr.cbuf.off += n;
                    fptr.cbuf.len -= n;
                    block.yield(context, asFixnum(context, c & 0xFFFFFFFF));
                }
            }
            fptr.NEED_NEWLINE_DECORATOR_ON_READ_CHECK();
            enc = fptr.inputEncoding(context.runtime);
            while (fptr.fillbuf(context) >= 0) {
                r = StringSupport.preciseLength(enc, fptr.rbuf.ptr, fptr.rbuf.off, fptr.rbuf.off + fptr.rbuf.len);
                if (StringSupport.MBCLEN_CHARFOUND_P(r) &&
                        (n = StringSupport.MBCLEN_CHARFOUND_LEN(r)) <= fptr.rbuf.len) {
                    c = StringSupport.codePoint(context, enc, fptr.rbuf.ptr, fptr.rbuf.off, fptr.rbuf.off + fptr.rbuf.len);
                    fptr.rbuf.off += n;
                    fptr.rbuf.len -= n;
                    block.yield(context, asFixnum(context, c & 0xFFFFFFFF));
                } else if (StringSupport.MBCLEN_INVALID_P(r)) {
                    throw argumentError(context, "invalid byte sequence in " + enc);
                } else if (StringSupport.MBCLEN_NEEDMORE_P(r)) {
                    byte[] cbuf = new byte[8];
                    int p = 0;
                    int more = StringSupport.MBCLEN_NEEDMORE_LEN(r);
                    if (more > cbuf.length) throw argumentError(context, "invalid byte sequence in " + enc);
                    more += n = fptr.rbuf.len;
                    if (more > cbuf.length) throw argumentError(context, "invalid byte sequence in " + enc);
                    while ((n = fptr.readBufferedData(cbuf, p, more)) > 0) {
                        p += n;
                        if ((more -= n) <= 0) break;

                        if (fptr.fillbuf(context) < 0) throw argumentError(context, "invalid byte sequence in " + enc);
                        if ((n = fptr.rbuf.len) > more) n = more;
                    }
                    r = enc.length(cbuf, 0, p);
                    if (!StringSupport.MBCLEN_CHARFOUND_P(r)) throw argumentError(context, "invalid byte sequence in " + enc);
                    c = enc.mbcToCode(cbuf, 0, p);
                    block.yield(context, asFixnum(context, c));
                } else {
                    continue;
                }
            }
        } finally {
            if (locked) fptr.unlock();
        }
        return this;
    }

    @JRubyMethod
    public IRubyObject each(final ThreadContext context, final Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each");

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), block);
    }

    @JRubyMethod(keywords = true)
    public IRubyObject each(final ThreadContext context, IRubyObject arg0, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each");

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), arg0, block, keywords);
    }

    @JRubyMethod(keywords = true)
    public IRubyObject each(final ThreadContext context, IRubyObject arg0, IRubyObject arg1, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each");

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), arg0, arg1, block, keywords);
    }

    @JRubyMethod(keywords = true)
    public IRubyObject each(final ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each");

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), arg0, arg1, arg2, block, keywords);
    }

    public IRubyObject each(final ThreadContext context, IRubyObject[]args, final Block block) {
        switch (args.length) {
            case 0:
                return each(context, block);
            case 1:
                return each(context, args[0], block);
            case 2:
                return each(context, args[0], args[1], block);
            case 3:
                return each(context, args[0], args[1], args[2], block);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    @JRubyMethod
    public IRubyObject each_line(final ThreadContext context, final Block block) {
        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line");

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), block);
    }

    @JRubyMethod(keywords = true)
    public IRubyObject each_line(final ThreadContext context, IRubyObject arg0, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;

        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", arg0);

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), arg0, block, keywords);
    }

    @JRubyMethod(keywords = true)
    public IRubyObject each_line(final ThreadContext context, IRubyObject arg0, IRubyObject arg1, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;

        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", arg0, arg1);

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), arg0, arg1, block, keywords);
    }

    @JRubyMethod(keywords = true)
    public IRubyObject each_line(final ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;

        if (!block.isGiven()) return enumeratorize(context.runtime, this, "each_line", arg0, arg1, arg2);

        return Getline.getlineCall(context, GETLINE_YIELD, this, getReadEncoding(context), arg0, arg1, arg2, block, keywords);
    }

    public IRubyObject each_line(final ThreadContext context, IRubyObject[]args, final Block block) {
        switch (args.length) {
            case 0:
                return each_line(context, block);
            case 1:
                return each_line(context, args[0], block);
            case 2:
                return each_line(context, args[0], args[1], block);
            case 3:
                return each_line(context, args[0], args[1], args[2], block);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    @Deprecated(since = "10.0")
    public IRubyObject lines(final ThreadContext context, Block block) {
        context.runtime.getWarnings().warn("IO#lines is deprecated; use #each_line instead");
        return each_line(context, block);
    }


    @JRubyMethod(name = "readlines")
    public RubyArray readlines(ThreadContext context) {
        return Getline.getlineCall(context, GETLINE_ARY, this, getReadEncoding(context));
    }

    @JRubyMethod(name = "readlines", keywords = true)
    public RubyArray readlines(ThreadContext context, IRubyObject arg0) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        return Getline.getlineCall(context, GETLINE_ARY, this, getReadEncoding(context), arg0, keywords);
    }

    @JRubyMethod(name = "readlines", keywords = true)
    public RubyArray readlines(ThreadContext context, IRubyObject arg0, IRubyObject arg1) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        return Getline.getlineCall(context, GETLINE_ARY, this, getReadEncoding(context), arg0, arg1, keywords);
    }

    @JRubyMethod(name = "readlines", keywords = true)
    public RubyArray readlines(ThreadContext context, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;
        return Getline.getlineCall(context, GETLINE_ARY, this, getReadEncoding(context), arg0, arg1, arg2, keywords);
    }

    private Encoding getReadEncoding(ThreadContext context) {
        return getOpenFileChecked().readEncoding(context.runtime);
    }

    public RubyArray readlines(ThreadContext context, IRubyObject[] args) {
        switch (args.length) {
            case 0:
                return readlines(context);
            case 1:
                return readlines(context, args[0]);
            case 2:
                return readlines(context, args[0], args[1]);
            case 3:
                return readlines(context, args[0], args[1], args[2]);
            default:
                Arity.raiseArgumentError(context, args.length, 0, 3);
                throw new AssertionError("BUG");
        }
    }

    @JRubyMethod(name = "to_io")
    public RubyIO to_io() {
    	return this;
    }

    @Override
    public String toString() {
        return inspect(getRuntime().getCurrentContext()).toString();
    }

    /* class methods for IO */

    // rb_io_s_foreach
    private static IRubyObject foreachInternal(ThreadContext context, IRubyObject recv, IRubyObject[] args, boolean keywords, Block block) {
        IRubyObject opt = ArgsUtil.getOptionsArg(context.runtime, args);
        RubyIO io = openKeyArgs(context, recv, args, opt);
        IRubyObject nil = context.nil;

        if (io == nil) return io;

        // io_s_foreach, roughly
        try {
            switch (args.length) {
                case 1:
                    Getline.getlineCall(context, GETLINE_YIELD, io, io.getReadEncoding(context), block);
                    break;
                case 2:
                    Getline.getlineCall(context, GETLINE_YIELD, io, io.getReadEncoding(context), args[1], block, keywords);
                    break;
                case 3:
                    Getline.getlineCall(context, GETLINE_YIELD, io, io.getReadEncoding(context), args[1], args[2], block, keywords);
                    break;
                case 4:
                    Getline.getlineCall(context, GETLINE_YIELD, io, io.getReadEncoding(context), args[1], args[2], args[3], block, keywords);
                    break;
                default:
                    Arity.checkArgumentCount(context, args.length - (keywords ? 1 : 0), 1, 3);
            }
        } finally {
            io.close();
            context.setLastLine(nil);
        }

        return nil;
    }

    @JRubyMethod(name = "foreach", required = 1, optional = 3, checkArity = false, meta = true, writes = LASTLINE, keywords = true)
    public static IRubyObject foreach(final ThreadContext context, IRubyObject recv, IRubyObject[] args, final Block block) {
        boolean keywords = (resetCallInfo(context) & CALL_KEYWORD) != 0;

        Arity.checkArgumentCount(context, args.length - (keywords ? 1 : 0), 1, 4);

        if (!block.isGiven()) return enumeratorize(context.runtime, recv, "foreach", args);

        return foreachInternal(context, recv, args, keywords, block);
    }

    public static RubyIO convertToIO(ThreadContext context, IRubyObject obj) {
        return TypeConverter.ioGetIO(context.runtime, obj);
    }

    @JRubyMethod(name = "select", required = 1, optional = 3, checkArity = false, meta = true)
    public static IRubyObject select(ThreadContext context, IRubyObject recv, IRubyObject[] argv) {
        if (Options.FIBER_SCHEDULER.load()) {
            IRubyObject scheduler = context.getFiberCurrentThread().getSchedulerCurrent();
            if (!scheduler.isNil()) {
                IRubyObject result = FiberScheduler.ioSelectv(context, scheduler, argv);
                if (result != UNDEF) return result;
            }
        }

        int argc = Arity.checkArgumentCount(context, argv, 1, 4);

        IRubyObject read, write, except, _timeout;
        read = write = except = _timeout = context.nil;

        switch (argc) {
            case 4:
                _timeout = argv[3];
            case 3:
                except = argv[2];
            case 2:
                write = argv[1];
            case 1:
                read = argv[0];
        }
        final Long timeout;
        if (_timeout.isNil()) {
            timeout = null;
        }
        else {
            try { // MRI calls to_f even if not respond_to? (or respond_to_missing?) :to_f
                _timeout = sites(context).to_f.call(context, _timeout, _timeout);
            }
            catch (RaiseException e) {
                TypeConverter.handleUncoercibleObject(context.runtime, _timeout, floatClass(context), true);
                throw e; // won't happen
            }
            final double t = _timeout.convertToFloat().asDouble(context);
            if ( t < 0 ) throw argumentError(context, "negative timeout");
            timeout = (long) (t * 1000); // ms
        }

        SelectExecutor args = new SelectExecutor(read, write, except, timeout);

        return args.go(context);
    }

    @JRubyMethod(optional = 1)
    public IRubyObject wait_readable(ThreadContext context, IRubyObject[] argv) {
        OpenFile fptr = this.getOpenFileChecked();

        fptr.checkReadable(context);

        long tv = prepareTimeout(context, argv);
        if (fptr.readPending() != 0) return context.tru;

        return doWait(context, fptr, tv, SelectionKey.OP_READ | SelectionKey.OP_ACCEPT);
    }

    /**
     * waits until input available or timed out and returns self, or nil when EOF reached.
     */
    @JRubyMethod(optional = 1)
    public IRubyObject wait_writable(ThreadContext context, IRubyObject[] argv) {
        OpenFile fptr = this.getOpenFileChecked();

        fptr.checkWritable(context);

        long tv = prepareTimeout(context, argv);

        return doWait(context, fptr, tv, SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE);
    }

    @JRubyMethod(optional = 2)
    public IRubyObject wait(ThreadContext context, IRubyObject[] argv) {
        OpenFile fptr = this.getOpenFileChecked();

        int ops = 0;

        if (argv.length == 2) {
            if (argv[1] instanceof RubySymbol) {
                RubySymbol sym = (RubySymbol) argv[1];
                switch (sym.asJavaString()) { // 7 bit comparison
                    case "r":
                    case "read":
                    case "readable":
                        ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ;
                        break;
                    case "w":
                    case "write":
                    case "writable":
                        ops |= SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE;
                        break;
                    case "rw":
                    case "read_write":
                    case "readable_writable":
                        ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ | SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE;
                        break;
                    default:
                        throw argumentError(context, "unsupported mode: " + sym);
                }
            } else if (argv[1] instanceof RubyFixnum fix) {
                var fixint = fix.asInt(context);
                if ((fixint & IOEvent.IO_READABLE.value) != 0) {
                    ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ;
                }
                if ((fixint & IOEvent.IO_WRITABLE.value) != 0) {
                    ops |= SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE;
                }
            } else {
                throw argumentError(context, "unsupported mode: " + argv[1].getType());
            }
        } else {
            ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ;
        }

        if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ && fptr.readPending() != 0) return context.tru;

        long tv = prepareTimeout(context, argv);

        return doWait(context, fptr, tv, ops);
    }

    private IRubyObject doWait(ThreadContext context, OpenFile fptr, long tv, int ops) {
        boolean ready = fptr.ready(context.runtime, context.getThread(), ops, tv);
        fptr.checkClosed();
        if (ready) return this;
        return context.nil;
    }

    private static long prepareTimeout(ThreadContext context, IRubyObject[] argv) {
        IRubyObject timeout;
        long tv;
        switch (argv.length) {
            case 2:
            case 1:
                timeout = argv[0];
                break;
            default:
                timeout = context.nil;
        }

        if (timeout.isNil()) {
            tv = -1;
        }
        else {
            tv = (long)(RubyTime.convertTimeInterval(context, timeout) * 1000);
            if (tv < 0) throw argumentError(context, "time interval must be positive");
        }
        return tv;
    }

    // MRI: rb_io_advise
    @JRubyMethod(required = 1, optional = 2, checkArity = false)
    public IRubyObject advise(ThreadContext context, IRubyObject[] argv) {
        IRubyObject advice, offset, len;
        advice = offset = len = context.nil;

        switch (argv.length) {
            case 3:
                len = argv[2];
            case 2:
                offset = argv[1];
            case 1:
                advice = argv[0];
                break;
            default:
                Arity.raiseArgumentError(context, argv, 1, 3);
        }

        PosixFadvise fadvise = adviceArgCheck(context, advice);
        int off = offset.isNil() ? 0 : toInt(context, offset);
        int l = len.isNil() ? 0 : toInt(context, len);
        POSIX posix = context.runtime.getNativePosix();
        RubyIO io = GetWriteIO();
        OpenFile fptr = io.getOpenFileChecked();

        if (!(posix instanceof Linux)) return context.nil;

        int fd = fptr.fd().realFileno;

        // TODO: may be able to manipulate some types of channels
        if (fd == -1) return context.nil;

        boolean locked = fptr.lock();
        try {
            int res = ((Linux) posix).posix_fadvise(fd, off, l, fadvise);
            if (res != 0) throw context.runtime.newErrnoFromInt(posix.errno(), "posix_fadvise");
        } finally {
            if (locked) fptr.unlock();
        }

        return context.nil;
    }

    // MRI: advice_arg_check
    static PosixFadvise adviceArgCheck(ThreadContext context, IRubyObject advice) {
        if (!(advice instanceof RubySymbol)) throw typeError(context, "advise must be a symbol");

        String adviceStr = advice.asJavaString();
        return switch (adviceStr) {
            default -> throw context.runtime.newNotImplementedError(rbInspect(context, advice).toString());
            case "normal", "sequential", "random", "willneed", "dontneed", "noreuse" ->
                    PosixFadvise.valueOf("POSIX_FADV_" + adviceStr.toUpperCase());
        };
    }

    public static void failIfDirectory(Ruby runtime, RubyString pathStr) {
        if (RubyFileTest.directory_p(runtime.getCurrentContext(), pathStr).isTrue()) {
            if (Platform.IS_WINDOWS) {
                throw runtime.newErrnoEACCESError(pathStr.asJavaString());
            } else {
                throw runtime.newErrnoEISDirError(pathStr.asJavaString());
            }
        }
    }

    // open_key_args
    private static RubyIO openKeyArgs(ThreadContext context, IRubyObject recv, IRubyObject[] argv, IRubyObject opt) {
        IRubyObject vmode = context.nil, vperm = context.nil, v;

        RubyString path = RubyFile.get_path(context, argv[0]);
        failIfDirectory(context.runtime, path); // only in JRuby
        // MRI increments args past 0 now, so remaining uses of args only see non-path args

        if (opt == context.nil) {
            vmode = asFixnum(context, ModeFlags.RDONLY);
            vperm = asFixnum(context, 0666);
        } else if ((v = ((RubyHash) opt).op_aref(context, asSymbol(context, "open_args"))) != context.nil) {
            RubyArray vAry = v.convertToArray();
            int n = vAry.size();

            Arity.checkArgumentCount(context, n, 0, 3);

            opt = ArgsUtil.getOptionsArg(context.runtime, vAry.toJavaArrayMaybeUnsafe());

            if (opt != context.nil) n--;
            switch (n) {
                case 2:
                    vperm = vAry.eltOk(1);
                case 1:
                    vmode = vAry.eltOk(0);
            }
        }

        return ioOpen(context, recv, path, vmode, vperm, opt);
    }

    // MRI: rb_io_open
    public static IRubyObject ioOpen(ThreadContext context, IRubyObject recv, IRubyObject filename, IRubyObject vmode, IRubyObject vperm, IRubyObject opt) {
        return ioOpen(context, recv, filename.asString(), vmode, vperm, opt);
    }

    static RubyIO ioOpen(ThreadContext context, IRubyObject recv, RubyString filename, IRubyObject vmode, IRubyObject vperm, IRubyObject opt) {
        int[] oflags_p = {0}, fmode_p = {0};
        ConvConfig convConfig = new ConvConfig();

        API.ModeAndPermission pm = new API.ModeAndPermission(vmode, vperm);
        EncodingUtils.extractModeEncoding(context, convConfig, pm, opt, oflags_p, fmode_p);
        vperm = vperm(pm);
        int perm = (vperm == null || vperm == context.nil) ? 0666 : toInt(context, vperm);

        return ioOpenGeneric(context, recv, filename, oflags_p[0], fmode_p[0], convConfig, perm);
    }

    // MRI: rb_io_open_generic
    private static RubyIO ioOpenGeneric(ThreadContext context, IRubyObject recv, IRubyObject filename, int oflags, int fmode, IOEncodable convconfig, int perm) {
        if ((filename instanceof RubyString name) && name.isEmpty()) throw context.runtime.newErrnoENOENTError();

        IRubyObject cmd;
        if ((recv == ioClass(context)) && (cmd = PopenExecutor.checkPipeCommand(context, filename)) != context.nil) {
            warnDeprecated(context, "IO process creation with a leading '|' is deprecated and will be removed in Ruby 4.0; use IO.popen instead");
            if (PopenExecutor.nativePopenAvailable(context.runtime)) {
                return (RubyIO) PopenExecutor.pipeOpen(context, cmd, OpenFile.ioOflagsModestr(context, oflags), fmode, convconfig);
            } else {
                throw argumentError(context, "pipe open is not supported without native subprocess logic");
            }
        }
        return (RubyIO) ((RubyFile) fileClass(context).allocate(context)).
                fileOpenGeneric(context, filename, oflags, fmode, convconfig, perm);
    }

    /**
     * binread is just like read, except it doesn't take options and it forces
     * mode to be "rb:ASCII-8BIT"
     *
     * @param context the current ThreadContext
     * @param recv the target of the call (IO or a subclass)
     * @param args arguments; path [, length [, offset]]
     * @return the binary contents of the given file, at specified length and offset
     */
    @JRubyMethod(meta = true, required = 1, optional = 2, checkArity = false)
    public static IRubyObject binread(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 3);
        IRubyObject path = RubyFile.get_path(context, args[0]);
        IRubyObject length = context.nil;
        IRubyObject offset = context.nil;
        IOEncodable convconfig = new IOEncodable.ConvConfig();

        int fmode = OpenFile.READABLE | OpenFile.BINMODE;
        OpenFlags oBinary = OpenFlags.O_BINARY;
        int oflags = OpenFlags.O_RDONLY.intValue() | (oBinary.defined() ? oBinary.intValue() : 0);

        if (argc > 2) {
            offset = args[2];
            length = args[1];
        } else if (argc > 1) {
            length = args[1];
        }
        convconfig.setEnc(ASCIIEncoding.INSTANCE);
        RubyIO file = ioOpenGeneric(context, recv, path, oflags, fmode, convconfig, 0);

        if (file.isNil()) return context.nil;

        try {
            if (!offset.isNil()) {
                file.seek(context, offset);
            }
            return file.read(context, length);
        } finally  {
            file.close();
        }
    }

    // Enebo: annotation processing forced me to do pangea method here...
    @JRubyMethod(name = "read", meta = true, required = 1, optional = 3, checkArity = false, keywords = true)
    public static IRubyObject read(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block unusedBlock) {
        IRubyObject keywords = IRRuntimeHelpers.receiveKeywords(context, args, false, true, false);
        int argc = Arity.checkArgumentCount(context, args, 1, 4);

        IRubyObject path = args[0];
        IRubyObject length, offset, options;
        length = offset = options = context.nil;

        { // rb_scan_args logic, basically
            if (argc > 3) {
                if (!(keywords instanceof RubyHash)) throw argumentError(context, args.length, 1, 4);
                options = args[3];
                offset = args[2];
                length = args[1];
            } else if (argc > 2) {
                if (keywords instanceof RubyHash) {
                    options = keywords;
                } else {
                    offset = args[2];
                }
                length = args[1];
            } else if (argc > 1) {
                if (keywords instanceof RubyHash) {
                    options = keywords;
                } else {
                    length = args[1];
                }
            }
            if (options == null) options = newHash(context);
        }

        long off;
        if (!offset.isNil() && (off = toLong(context, offset)) < 0) {
            throw argumentError(context, "negative offset " + off + " given");
        }

        RubyIO file = openKeyArgs(context, recv, new IRubyObject[]{path, length, offset}, options);

        try {
            if (offset != context.nil) {
                // protect logic around here in MRI?
                file.seek(context, offset);
            }
            return file.read(context, length);
        } finally  {
            file.close();
        }
    }

    public static IRubyObject read(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        return read(context, recv, args, Block.NULL_BLOCK);
    }

    // rb_io_s_binwrite
    @JRubyMethod(meta = true, required = 2, optional = 2, checkArity = false, keywords = true)
    public static IRubyObject binwrite(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        return ioStaticWrite(context, recv, args, true);
    }

    // MRI: rb_io_s_write
    @JRubyMethod(name = "write", meta = true, required = 2, optional = 2, checkArity = false, keywords = true)
    public static IRubyObject write(ThreadContext context, IRubyObject recv, IRubyObject[] argv) {
        return (ioStaticWrite(context, recv, argv, false));
    }

    // MRI: io_s_write
    public static IRubyObject ioStaticWrite(ThreadContext context, IRubyObject recv, IRubyObject[] argv, boolean binary) {
        IRubyObject keywords = IRRuntimeHelpers.receiveKeywords(context, argv, false, true, false);
        final Ruby runtime = context.runtime;
        IRubyObject string, offset, opt;
        string = offset = opt = context.nil;

        switch (argv.length) {
            case 4:
                if (!(keywords instanceof RubyHash)) throw runtime.newArgumentError(argv.length, 2, 3);
                opt = keywords;
                offset = argv[2];
                string = argv[1];
                break;
            case 3:
                if (keywords instanceof RubyHash) {
                    opt = keywords;
                } else {
                    offset = argv[2];
                }
                string = argv[1];
                break;
            case 2:
                string = argv[1];
                break;
            default:
                Arity.raiseArgumentError(context, argv.length, 2, 4);
        }

        final RubyHash optHash;
        optHash = opt == context.nil ? newHash(context) : ((RubyHash) opt).dupFast(context);

        final RubySymbol modeSym = asSymbol(context, "mode");
        if ( optHash.op_aref(context, modeSym) == context.nil ) {
            int mode = OpenFlags.O_WRONLY.intValue() | OpenFlags.O_CREAT.intValue();
            if ( OpenFlags.O_BINARY.defined() ) {
                if ( binary ) mode |= OpenFlags.O_BINARY.intValue();
            }
            if ( offset == context.nil ) mode |= OpenFlags.O_TRUNC.intValue();
            optHash.op_aset(context, modeSym, asFixnum(context, mode));
        }

        IRubyObject _io = openKeyArgs(context, recv, argv, optHash);
        if ( _io == context.nil ) return context.nil;
        final RubyIO io = (RubyIO) _io;

        if ( ! OpenFlags.O_BINARY.defined() ) {
            if ( binary ) io.binmode();
        }

        if ( offset != context.nil ) {
            seekBeforeAccess(context, io, offset);
        }

        try {
            return io.write(context, string, false);
        }
        finally { ioClose(context, io); }
    }

    static IRubyObject seekBeforeAccess(ThreadContext context, RubyIO io, IRubyObject offset) {
        io.setBinmode();
        return io.seek(context, offset);
    }

    // rb_io_s_readlines
    @JRubyMethod(name = "readlines", required = 1, optional = 2, checkArity = false, meta = true, keywords = true)
    public static IRubyObject readlines(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block unusedBlock) {
        // FIXME: readlines and friends all look at kwargs deep in IO code but we also do it at beginning of methods
        // as well.  Any internal dyndispatch will reset callInfo like in the next few lines.  I save callInfo here
        // to be restored to save it for the deeper kwargs code.
        int callInfo = context.callInfo;
        IRubyObject opt = ArgsUtil.getOptionsArg(context.runtime, args);

        final RubyIO io = openKeyArgs(context, recv, args, opt);
        context.callInfo = callInfo;
        try {
            switch (args.length) {
                case 1:
                    return io.readlines(context);
                case 2:
                    return io.readlines(context, args[1]);
                case 3:
                    return io.readlines(context, args[1], args[2]);
                case 4:
                    return io.readlines(context, args[1], args[2], args[3]);
                default:
                    Arity.raiseArgumentError(context, args.length, 1, 3);
                    throw new AssertionError("BUG");
            }
        } finally { io.close(); }
    }

    private void setupPopen(ThreadContext context, ModeFlags modes, POpenProcess process) throws RaiseException {
        openFile.setMode(modes.getOpenFileFlags() | OpenFile.SYNC);
        openFile.setProcess(process);

        if (openFile.isReadable()) {
            Channel inChannel;
            if (process.getInput() != null) {
                // NIO-based
                inChannel = process.getInput();
            } else {
                // Stream-based
                inChannel = Channels.newChannel(process.getInputStream());
            }

            ChannelFD main = new ChannelFD(inChannel, context.runtime.getPosix(), context.runtime.getFilenoUtil());

            openFile.setFD(main);
        }

        if (openFile.isWritable() && process.hasOutput()) {
            Channel outChannel;
            if (process.getOutput() != null) {
                // NIO-based
                outChannel = process.getOutput();
            } else {
                outChannel = Channels.newChannel(process.getOutputStream());
            }

            ChannelFD pipe = new ChannelFD(outChannel, context.runtime.getPosix(), context.runtime.getFilenoUtil());

            // if also readable, attach as tied IO; otherwise, primary IO
            if (openFile.isReadable()) {
                RubyIO writeIO = new RubyIO(context.runtime, ioClass(context));
                writeIO.initializeCommon(context, pipe, asFixnum(context, OpenFlags.O_WRONLY.longValue()), context.nil);

                openFile.tiedIOForWriting = writeIO;
                setInstanceVariable("@tied_io_for_writing", writeIO);
            } else {
                openFile.setFD(pipe);
            }
        }
    }

    private static final class RubyPOpen {
        final RubyString cmd;
        final IRubyObject[] cmdPlusArgs;
        final RubyHash env;

        RubyPOpen(ThreadContext context, IRubyObject[] args) {
            IRubyObject[] _cmdPlusArgs;
            IRubyObject _env;
            IRubyObject _cmd;

            int firstArg = 0;
            int argc = args.length;

            if (argc > 0 && !(_env = TypeConverter.checkHashType(context.runtime, args[0])).isNil()) {
                if (argc < 2) throw argumentError(context, 1, 2);
                firstArg++;
                argc--;
            } else {
                _env = null;
            }

            IRubyObject arg0 = args[firstArg].checkArrayType();

            if (arg0.isNil()) {
                if ((arg0 = TypeConverter.checkStringType(context.runtime, args[firstArg])).isNil()) {
                    throw typeError(context, args[firstArg], "String");
                }
                _cmdPlusArgs = null;
                _cmd = arg0;
            } else {
                RubyArray arg0Ary = (RubyArray) arg0;
                if (arg0Ary.isEmpty()) throw argumentError(context, "wrong number of arguments");
                if (arg0Ary.eltOk(0) instanceof RubyHash) {
                    // leading hash, use for env
                    _env = arg0Ary.delete_at(context,0);
                }
                if (arg0Ary.isEmpty()) throw argumentError(context, "wrong number of arguments");
                if (arg0Ary.size() > 1 && arg0Ary.eltOk(arg0Ary.size() - 1) instanceof RubyHash) {
                    // trailing hash, use for opts
                    _env = arg0Ary.eltOk(arg0Ary.size() - 1);
                }
                _cmdPlusArgs = arg0Ary.toJavaArray(context);
                _cmd = _cmdPlusArgs[0];
            }

            if (Platform.IS_WINDOWS) {
                String commandString = _cmd.convertToString().toString().replace('/', '\\');
                _cmd = newString(context, commandString);
                if (_cmdPlusArgs != null) _cmdPlusArgs[0] = _cmd;
            } else {
                _cmd = _cmd.convertToString();
                if (_cmdPlusArgs != null) _cmdPlusArgs[0] = _cmd;
            }

            this.cmd = (RubyString) _cmd;
            this.cmdPlusArgs = _cmdPlusArgs;
            this.env = (RubyHash) _env;
        }
    }

    @JRubyMethod(name = "popen", required = 1, optional = 2, checkArity = false, meta = true)
    public static IRubyObject popen(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        int argc = Arity.checkArgumentCount(context, args, 1, 3);

        Ruby runtime = context.runtime;

        if (PopenExecutor.nativePopenAvailable(runtime)) {
            // new native popen logic
            return PopenExecutor.popen(context, args, (RubyClass)recv, block);
        }

        // old JDK popen logic
        IRubyObject pmode = null;
        RubyHash options = null;
        IRubyObject tmp;

        int firstArg = 0;

        if (argc > 0 && !TypeConverter.checkHashType(runtime, args[0]).isNil()) {
            firstArg++;
            argc--;
        }

        if (argc > 0 && !(tmp = TypeConverter.checkHashType(runtime, args[argc - 1])).isNil()) {
            options = (RubyHash)tmp;
            argc--;
        }

        if (argc > 1) {
            pmode = args[firstArg + 1];
        }

        RubyIO io = new RubyIO(runtime, (RubyClass) recv);

        io.MakeOpenFile();

        IRubyObject vperm = asFixnum(context, 0);
        API.ModeAndPermission pm = new API.ModeAndPermission(pmode, vperm);
        int[] oflags_p = {0}, fmode_p = {0};
        EncodingUtils.extractModeEncoding(context, io, pm, options, oflags_p, fmode_p);
        ModeFlags modes = ModeFlags.createModeFlags(oflags_p[0]);

        // FIXME: Reprocessing logic twice for now...
        // for 1.9 mode, strip off the trailing options hash, if there
        if (argc > 1 && args[argc - 1] instanceof RubyHash) {
            options = (RubyHash) args[argc - 1];
            args = ArraySupport.newCopy(args, 0, argc - 1);
        }

        RubyPOpen pOpen = new RubyPOpen(context, args);

        if (isDash(pOpen.cmd)) throw runtime.newNotImplementedError("popen(\"-\") is unimplemented");

        try {
            ShellLauncher.POpenProcess process;
            if (pOpen.cmdPlusArgs == null) {
                process = ShellLauncher.popen(runtime, pOpen.cmd, modes);
            } else {
                process = ShellLauncher.popen(runtime, pOpen.cmdPlusArgs, pOpen.env, modes);
            }

            if (options != null) {
                checkUnsupportedOptions(context, options, UNSUPPORTED_SPAWN_OPTIONS, "unsupported popen option");
            }

            io.setupPopen(context, modes, process);

            if (block.isGiven()) {
                IRubyObject returnedBlock = ensureYieldClose(context, io, block);
                // RubyStatus uses real native status now, so we unshift Java's shifted exit status
                context.setLastExitStatus(RubyProcess.RubyStatus.newProcessStatus(runtime, process.waitFor() << 8, ShellLauncher.getPidFromProcess(process)));
                return returnedBlock;
            }
            return io;
        } catch (IOException e) {
            throw runtime.newIOErrorFromException(e);
        } catch (InterruptedException e) {
            throw runtime.newThreadError("unexpected interrupt");
        }
    }

    public static IRubyObject pipe(ThreadContext context, IRubyObject recv) {
        return pipe(context, recv, Block.NULL_BLOCK);
    }

    @JRubyMethod(name = "pipe", meta = true)
    public static IRubyObject pipe(ThreadContext context, IRubyObject klass, Block block) {
        return pipe(context, klass, context.nil, context.nil, context.nil, block);
    }

    @JRubyMethod(name = "pipe", meta = true)
    public static IRubyObject pipe(ThreadContext context, IRubyObject klass, IRubyObject arg0, Block block) {
        IRubyObject opt = TypeConverter.checkHashType(context.runtime, arg0);

        return pipe(context, klass, opt.isNil() ? arg0 : context.nil, context.nil, opt, block);
    }

    @JRubyMethod(name = "pipe", meta = true)
    public static IRubyObject pipe(ThreadContext context, IRubyObject klass, IRubyObject arg0, IRubyObject arg1, Block block) {
        IRubyObject opt = TypeConverter.checkHashType(context.runtime, arg1);

        return pipe(context, klass, arg0, !opt.isNil() ? context.nil : arg1, opt, block);
    }

    @JRubyMethod(name = "pipe", meta = true)
    public static IRubyObject pipe(ThreadContext context, IRubyObject klass, IRubyObject v1, IRubyObject v2, IRubyObject opt, Block block) {
        Ruby runtime = context.runtime;

        RubyIO r, w;
        OpenFile fptr, fptr2;
        int[] fmode_p = {0};
        IRubyObject ret;

        PosixShim posix = new PosixShim(runtime);
        Channel[] fds = posix.pipe();
        if (fds == null) throw runtime.newErrnoFromErrno(posix.getErrno(), "opening pipe");

//        args[0] = klass;
//        args[1] = INT2NUM(pipes[0]);
//        args[2] = INT2FIX(O_RDONLY);
//        r = rb_protect(io_new_instance, (VALUE)args, &state);
//        if (state) {
//            close(pipes[0]);
//            close(pipes[1]);
//            rb_jump_tag(state);
//        }
        r = new RubyIO(runtime, (RubyClass) klass);
        r.initializeCommon(context,
                new ChannelFD(fds[0], runtime.getPosix(), runtime.getFilenoUtil()), asFixnum(context, OpenFlags.O_RDONLY.longValue()), context.nil);
        fptr = r.getOpenFileChecked();

        r.setEncoding(context, v1, v2, opt);

//        args[1] = INT2NUM(pipes[1]);
//        args[2] = INT2FIX(O_WRONLY);
//        w = rb_protect(io_new_instance, (VALUE)args, &state);
//        if (state) {
//            close(pipes[1]);
//            if (!NIL_P(r)) rb_io_close(r);
//            rb_jump_tag(state);
//        }
        w = new RubyIO(runtime, (RubyClass) klass);
        w.initializeCommon(context,
                new ChannelFD(fds[1], runtime.getPosix(), runtime.getFilenoUtil()), asFixnum(context, OpenFlags.O_WRONLY.longValue()), context.nil);
        fptr2 = w.getOpenFileChecked();
        fptr2.setSync(true);

        EncodingUtils.extractBinmode(context, opt, fmode_p);

        if (EncodingUtils.DEFAULT_TEXTMODE != 0) {
            if ((fptr.getMode() & OpenFile.TEXTMODE) != 0 && (fmode_p[0] & OpenFile.BINMODE) != 0) {
                fptr.setMode(fptr.getMode() & ~OpenFile.TEXTMODE);
                // TODO: setmode O_BINARY means what via NIO?
//                setmode(fptr->fd, O_BINARY);
            }
            if (Platform.IS_WINDOWS) { // #if defined(RUBY_TEST_CRLF_ENVIRONMENT) || defined(_WIN32)
                if ((fptr.encs.ecflags & EncodingUtils.ECONV_DEFAULT_NEWLINE_DECORATOR) != 0) {
                    fptr.encs.ecflags |= EConvFlags.UNIVERSAL_NEWLINE_DECORATOR;
                }
            }
        }
        fptr.setMode(fptr.getMode() | fmode_p[0]);

        if (EncodingUtils.DEFAULT_TEXTMODE != 0) {
            if ((fptr2.getMode() & OpenFile.TEXTMODE) != 0 && (fmode_p[0] & OpenFile.BINMODE) != 0) {
                fptr2.setMode(fptr2.getMode() & ~OpenFile.TEXTMODE);
                // TODO: setmode O_BINARY means what via NIO?
//                setmode(fptr2->fd, O_BINARY);
            }
        }
        fptr2.setMode(fptr2.getMode() | fmode_p[0]);

        ret = newArray(context, r, w);
        return block.isGiven() ?
            ensureYieldClosePipes(context, ret, r, w, block) : ret;
    }

    // MRI: rb_ensure(... pipe_pair_close ...)
    public static IRubyObject ensureYieldClosePipes(ThreadContext context, IRubyObject obj, RubyIO r, RubyIO w, Block block) {
        try {
            return block.yield(context, obj);
        } finally {
            pipePairClose(context, r, w);
        }
    }

    // MRI: pipe_pair_close
    private static void pipePairClose(ThreadContext context, RubyIO r, RubyIO w) {
        try {
            ioClose(context, r);
        } finally {
            ioClose(context, w);
        }
    }

    @JRubyMethod(name = "copy_stream", meta = true)
    public static IRubyObject copy_stream(ThreadContext context, IRubyObject recv, IRubyObject arg0, IRubyObject arg1) {
        return copyStreamCommon(context, arg0, arg1, null, null);
    }

    @JRubyMethod(name = "copy_stream", meta = true)
    public static IRubyObject copy_stream(ThreadContext context, IRubyObject recv, IRubyObject arg0, IRubyObject arg1, IRubyObject arg2) {
        RubyInteger length = arg2.isNil() ? null : toInteger(context, arg2);
        return copyStreamCommon(context, arg0, arg1, length, null);
    }

    @JRubyMethod(name = "copy_stream", required = 2, optional = 2, checkArity = false, meta = true)
    public static IRubyObject copy_stream(ThreadContext context, IRubyObject recv, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 2, 4);
        RubyInteger length = argc >= 3 && !args[2].isNil() ? toInteger(context, args[2]) : null;
        RubyInteger offset = argc == 4 && !args[3].isNil() ? toInteger(context, args[3]) : null;

        return copyStreamCommon(context, args[0], args[1], length, offset);
    }

    private static RubyFixnum copyStreamCommon(ThreadContext context, IRubyObject arg1, IRubyObject arg2, RubyInteger length, RubyInteger offset) {
        Ruby runtime = context.runtime;

        RubyIO io1 = null;
        RubyIO io2 = null;

        Channel channel1 = null;
        Channel channel2 = null;

        IOSites sites = sites(context);

        // whether we were given an IO or had to produce a channel locally in some other way
        boolean userProvidedReadIO = false;

        // whether we constructed, and should close, the indicated IO
        boolean local1 = false;
        boolean local2 = false;

        try {
            var ArgsFile = argsFile(context);

            if (arg1 == ArgsFile || !(arg1 instanceof RubyFile || arg1 instanceof RubyString || arg1.respondsTo("to_path"))) {
                if (offset != null) {
                    throw argumentError(context, "cannot specify src_offset for non-IO");
                }

                if (sites.respond_to_read.respondsTo(context, arg1, arg1, true)) {
                    channel1 = new IOChannel.IOReadableByteChannel(arg1);
                } else if (sites.respond_to_readpartial.respondsTo(context, arg1, arg1, true)) {
                    channel1 = new IOChannel.IOReadableByteChannel(arg1, "readpartial");
                }
            } else {
                IRubyObject tmpIO = TypeConverter.ioCheckIO(runtime, arg1);
                if (!tmpIO.isNil()) {
                    arg1 = tmpIO;
                } else if (!(arg1 instanceof RubyFile)) {
                    RubyString path = RubyFile.get_path(context, arg1);
                    arg1 = RubyFile.open(context, fileClass(context), new IRubyObject[]{path, asFixnum(context, ModeFlags.RDONLY)}, Block.NULL_BLOCK);
                    local1 = true;
                }

                io1 = (RubyIO) arg1;
                userProvidedReadIO = true;
            }

            // for instance IO, just use its channel
            if (io1 instanceof RubyIO) {
                io1.openFile.checkReadable(context);
                channel1 = io1.getChannel();
            }

            if (arg2 == ArgsFile || !(arg2 instanceof RubyFile || arg2 instanceof RubyString || arg2.respondsTo("to_path"))) {
                if (sites.respond_to_write.respondsTo(context, arg2, arg2, true)) {
                    channel2 = new IOChannel.IOWritableByteChannel(arg2);
                }
            } else {
                IRubyObject tmpIO = TypeConverter.ioCheckIO(runtime, arg2);
                if (!tmpIO.isNil()) {
                    arg2 = tmpIO;
                } else if (!(arg2 instanceof RubyFile)) {
                    RubyString path = RubyFile.get_path(context, arg2);
                    arg2 = RubyFile.open(context, fileClass(context),
                            new IRubyObject[]{path, asFixnum(context, ModeFlags.WRONLY | ModeFlags.CREAT | ModeFlags.TRUNC)}, Block.NULL_BLOCK);
                    local2 = true;
                }

                io2 = (RubyIO) arg2;
            }

            // for instanceof IO, just use its write channel
            if (io2 instanceof RubyIO) {
                io2 = io2.GetWriteIO();
                io2.openFile.checkWritable(context);
                io2.flush(context);
                channel2 = io2.getChannel();
            }

            if (!(channel1 instanceof ReadableByteChannel)) throw runtime.newIOError("from IO is not readable");
            if (!(channel2 instanceof WritableByteChannel)) throw runtime.newIOError("to IO is not writable");

            boolean locked = false;
            OpenFile fptr1 = null;

            // attempt to preserve position of original and lock user IO for duration of copy
            if (userProvidedReadIO) {
                fptr1 = io1.getOpenFileChecked();

                locked = fptr1.lock();
            }

            try {
                long pos = 0;
                long size = 0;

                if (userProvidedReadIO) {
                    pos = fptr1.tell(context);
                }

                try {
                    if ((channel1 instanceof FileChannel)) {
                        FileChannel from = (FileChannel) channel1;
                        WritableByteChannel to = (WritableByteChannel) channel2;
                        long remaining = length == null ? from.size() : length.asLong(context);
                        long position = offset == null ? from.position() : offset.asLong(context);

                        size = transfer(from, to, remaining, position);
                    } else {
                        long remaining = length == null ? -1 : length.asLong(context);
                        long position = offset == null ? -1 : offset.asLong(context);
                        if ((channel2 instanceof FileChannel)) {
                            ReadableByteChannel from = (ReadableByteChannel) channel1;
                            FileChannel to = (FileChannel) channel2;

                            size = transfer(context, from, to, remaining, position);
                        } else {
                            ReadableByteChannel from = (ReadableByteChannel) channel1;
                            WritableByteChannel to = (WritableByteChannel) channel2;

                            size = transfer(context, from, to, remaining, position);
                        }
                    }

                    return asFixnum(context, size);
                } catch (EOFError eof) {
                    // ignore EOF, reached end of input
                    return asFixnum(context, size);
                } catch (IOException ioe) {
                    throw runtime.newIOErrorFromException(ioe);
                } finally {
                    if (userProvidedReadIO) {
                        if (offset != null) {
                            fptr1.seek(context, pos, PosixShim.SEEK_SET);
                        } else {
                            fptr1.seek(context, pos + size, PosixShim.SEEK_SET);
                        }
                    }
                }
            } finally {
                if (userProvidedReadIO && locked) fptr1.unlock();
            }
        } finally {

            // Clean up locally-created IO objects
            if (local1) {
                try {
                    io1.close();} catch (Exception e) {}
            }
            if (local2) {
                try {
                    io2.close();} catch (Exception e) {}
            }

        }
    }

    private static long transfer(ThreadContext context, ReadableByteChannel from, FileChannel to, long length, long position) throws IOException {
        // handle large files on 32-bit JVMs
        long chunkSize = 128 * 1024 * 1024;
        long transferred = 0;
        long bytes;
        long startPosition = to.position();

        if (position != -1) {
            if (from instanceof NativeSelectableChannel) {
                int ret = context.runtime.getPosix().lseek(((NativeSelectableChannel)from).getFD(), position, PosixShim.SEEK_SET);
                if (ret == -1) {
                    throw context.runtime.newErrnoFromErrno(Errno.valueOf(context.runtime.getPosix().errno()), from.toString());
                }
            }
        }
        if (length > 0) {
            while ((bytes = to.transferFrom(from, startPosition+transferred, Math.min(chunkSize, length))) > 0) {
                transferred += bytes;
                length -= bytes;
            }
        } else {
            while ((bytes = to.transferFrom(from, startPosition+transferred, chunkSize)) > 0) {
                transferred += bytes;
            }
        }
        // transforFrom does not change position of target
        to.position(startPosition + transferred);

        return transferred;
    }

    private static long transfer(FileChannel from, WritableByteChannel to, long remaining, long position) throws IOException {
        // handle large files on 32-bit JVMs
        long chunkSize = 128 * 1024 * 1024;
        long transferred = 0;

        if (remaining < 0) remaining = from.size();
        while (remaining > 0) {
            long count = Math.min(remaining, chunkSize);
            long n = from.transferTo(position, count, to);
            if (n == 0) {
                break;
            }

            position += n;
            remaining -= n;
            transferred += n;
        }

        return transferred;
    }

    private static long transfer(ThreadContext context, ReadableByteChannel from, WritableByteChannel to, long length, long position) throws IOException {
        int chunkSize = 8 * 1024;
        ByteBuffer buffer = ByteBuffer.allocate(chunkSize);
        long transferred = 0;

        if (position != -1) {
            if (from instanceof NativeSelectableChannel nativeSelectableChannel) {
                int ret = context.runtime.getPosix().lseek(nativeSelectableChannel.getFD(), position, PosixShim.SEEK_SET);
                if (ret == -1) {
                    throw context.runtime.newErrnoFromErrno(Errno.valueOf(context.runtime.getPosix().errno()), from.toString());
                }
            } else if (from instanceof SeekableByteChannel seekableByteChannel) {
                seekableByteChannel.position(position);
            } else {
                throw context.runtime.newErrnoESPIPEError();
            }
        }

        while (true) {
            context.blockingThreadPoll();

            if (length > 0 && length < chunkSize) {
                // last read should limit to remaining length
                limitBuffer(buffer, (int)length);
            }
            long n = from.read(buffer);

            if (n == -1) break;

            // write buffer fully and then clear
            flipBuffer(buffer);
            long w = 0;
            while (w < n) {
                w += to.write(buffer);

                if (to instanceof IOChannel) {
                    // if this channel is wrapping an IO, we assume write wrote as much as possible (GH-6555)
                    break;
                }
            }
            clearBuffer(buffer);

            // add only written count since it may not match read count for a false IO (GH-6555)
            transferred += w;
            if (length > 0) {
                length -= n;
                if (length <= 0) break;
            }

            if (!from.isOpen()) break;
        }

        return transferred;
    }

    @JRubyMethod(name = "try_convert", meta = true)
    public static IRubyObject tryConvert(ThreadContext context, IRubyObject recv, IRubyObject arg) {
        return ( arg instanceof RubyObject &&
                sites(context).respond_to_to_io.respondsTo(context, arg, arg, true) ) ?
                    convertToIO(context, arg) : context.nil;
    }

    @JRubyMethod(name = "pread")
    public IRubyObject pread(ThreadContext context, IRubyObject len, IRubyObject offset) {
        return pread(context, len, offset, null);
    }

    @JRubyMethod(name = "pread")
    public IRubyObject pread(ThreadContext context, IRubyObject _length, IRubyObject _from, IRubyObject str) {
        int length = toInt(context, _length);
        int from = toInt(context, _from);

        RubyString string = EncodingUtils.setStrBuf(context.runtime, str, length);
        if (length == 0) return string;

        OpenFile fptr = getOpenFile();
        fptr.checkByteReadable(context);

        ChannelFD fd = fptr.fd();
        fptr.checkClosed();

        ByteList strByteList = string.getByteList();
        ByteBuffer wrap = ByteBuffer.wrap(strByteList.unsafeBytes(), strByteList.begin(), length);
        int read = OpenFile.preadInternal(context, fptr, fd, wrap, from, length);

        string.setReadLength(read);

        return string;
    }

    @JRubyMethod(name = "pwrite")
    public IRubyObject pwrite(ThreadContext context, IRubyObject str, IRubyObject offset) {
        RubyString string = str instanceof RubyString str2 ? str2 : str.convertToString();
        int off = toInt(context, offset);
        OpenFile fptr = GetWriteIO().getOpenFile();

        fptr.checkWritable(context);

        ChannelFD fd = fptr.fd();
        RubyString buf = string.newFrozen();
        ByteList strByteList = buf.getByteList();
        int length = strByteList.realSize();
        ByteBuffer wrap = ByteBuffer.wrap(strByteList.unsafeBytes(), strByteList.begin(), length);

        int written = OpenFile.pwriteInternal(context, fptr, fd, wrap, off, length);

        return asFixnum(context, written);
    }

    @JRubyMethod(optional = 1)
    public static IRubyObject wait_readable(ThreadContext context, IRubyObject _io, IRubyObject[] argv) {
        RubyIO io = (RubyIO)_io;
        OpenFile fptr = io.getOpenFileChecked();

        fptr.checkReadable(context);

        long tv = prepareTimeout(context, argv);

        if (fptr.readPending() != 0) return context.tru;

        return doWait(context, io, fptr, tv, SelectionKey.OP_READ | SelectionKey.OP_ACCEPT);
    }

    /**
     * waits until input available or timed out and returns self, or nil when EOF reached.
     */
    @JRubyMethod(optional = 1)
    public static IRubyObject wait_writable(ThreadContext context, IRubyObject _io, IRubyObject[] argv) {
        RubyIO io = (RubyIO)_io;

        OpenFile fptr = io.getOpenFileChecked();

        fptr.checkWritable(context);

        long tv = prepareTimeout(context, argv);

        return doWait(context, io, fptr, tv, SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE);
    }

    @JRubyMethod(optional = 2)
    public static IRubyObject wait(ThreadContext context, IRubyObject _io, IRubyObject[] argv) {
        RubyIO io = (RubyIO)_io;

        OpenFile fptr = io.getOpenFileChecked();

        int ops = 0;

        if (argv.length == 2) {
            if (argv[1] instanceof RubySymbol) {
                RubySymbol sym = (RubySymbol) argv[1];
                switch (sym.asJavaString()) { // 7 bit comparison
                    case "r":
                    case "read":
                    case "readable":
                        ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ;
                        break;
                    case "w":
                    case "write":
                    case "writable":
                        ops |= SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE;
                        break;
                    case "rw":
                    case "read_write":
                    case "readable_writable":
                        ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ | SelectionKey.OP_CONNECT | SelectionKey.OP_WRITE;
                        break;
                    default:
                        throw argumentError(context, "unsupported mode: " + sym);
                }
            } else {
                throw argumentError(context, "unsupported mode: " + argv[1].getType());
            }
        } else {
            ops |= SelectionKey.OP_ACCEPT | SelectionKey.OP_READ;
        }

        if ((ops & SelectionKey.OP_READ) == SelectionKey.OP_READ && fptr.readPending() != 0) return context.tru;

        long tv = prepareTimeout(context, argv);

        return doWait(context, io, fptr, tv, ops);
    }

    private static IRubyObject doWait(ThreadContext context, RubyIO io, OpenFile fptr, long tv, int ops) {
        boolean ready = fptr.ready(context.runtime, context.getThread(), ops, tv);
        fptr.checkClosed();
        if (ready) return io;
        return context.nil;
    }

    @JRubyMethod(name = {"path", "to_path"})
    public IRubyObject path(ThreadContext context) {
        final String path = getPath();
        return path != null ? newString(context, path) : context.nil;
    }

    public String getPath() {
        if (openFile == null) return null;
        return openFile.getPath();
    }

    protected void setPath(String path) {
        if (openFile == null) return;
        openFile.setPath(path);
    }

    /**
     * Add a thread to the list of blocking threads for this IO.
     *
     * @param thread A thread blocking on this IO
     */
    public void addBlockingThread(RubyThread thread) {
        OpenFile fptr = openFile;
        if (fptr != null) fptr.addBlockingThread(thread);
    }

    /**
     * Remove a thread from the list of blocking threads for this IO.
     *
     * @param thread A thread blocking on this IO
     */
    public void removeBlockingThread(RubyThread thread) {
        OpenFile fptr = this.openFile;
        if (fptr != null) fptr.removeBlockingThread(thread);
    }

    /**
     * See http://ruby-doc.org/core-1.9.3/IO.html#method-c-new for the format of modes in options
     */
    protected IOOptions updateIOOptionsFromOptions(ThreadContext context, RubyHash options, IOOptions ioOptions) {
        if (options == null || options == context.nil) return ioOptions;

        final Ruby runtime = context.runtime;

        final IRubyObject mode = options.fastARef(asSymbol(context, "mode"));
        if (mode != null) {
            ioOptions = parseIOOptions(mode);
        }

        // This duplicates the non-error behavior of MRI 1.9: the
        // :binmode option is ORed in with other options. It does
        // not obliterate what came before.

        final RubySymbol binmode = asSymbol(context, "binmode");
        if (isTrue(options.fastARef(binmode))) {
            ioOptions = newIOOptions(runtime, ioOptions, ModeFlags.BINARY);
        }

        // This duplicates the non-error behavior of MRI 1.9: the
        // :binmode option is ORed in with other options. It does
        // not obliterate what came before.

        if (isTrue(options.fastARef(binmode))) {
            ioOptions = newIOOptions(runtime, ioOptions, ModeFlags.BINARY);
        }

        if (isTrue(options.fastARef(asSymbol(context, "textmode")))) {
            ioOptions = newIOOptions(runtime, ioOptions, ModeFlags.TEXT);
        }

        // TODO: Waaaay different than MRI.  They uniformly have all opening logic
        // do a scan of args before anything opens.  We do this logic in a less
        // consistent way.  We should consider re-impling all IO/File construction
        // logic.
        IRubyObject open_args = options.fastARef(asSymbol(context, "open_args"));
        if (open_args != null) {
            RubyArray openArgs = open_args.convertToArray();

            for (int i = 0; i < openArgs.size(); i++) {
                IRubyObject arg = openArgs.eltInternal(i);

                if (arg instanceof RubyString) { // Overrides all?
                    ioOptions = newIOOptions(runtime, arg.asJavaString());
                } else if (arg instanceof RubyFixnum fixnum) {
                    ioOptions = newIOOptions(runtime, fixnum.getValue());
                } else if (arg instanceof RubyHash hash) {
                    ioOptions = updateIOOptionsFromOptions(context, hash, ioOptions);
                }
            }
        }

        EncodingUtils.ioExtractEncodingOption(context, this, options, null);

        return ioOptions;
    }

    private static boolean isTrue(IRubyObject val) { return val != null && val.isTrue(); }

    static final Set<String> ALL_SPAWN_OPTIONS;
    static final String[] UNSUPPORTED_SPAWN_OPTIONS;

    static {
        String[] SPAWN_OPTIONS = new String[] {
                "unsetenv_others",
                "prgroup",
                "new_pgroup",
                "rlimit_resourcename",
                "chdir",
                "umask",
                "in",
                "out",
                "err",
                "close_others"
        };
        UNSUPPORTED_SPAWN_OPTIONS = new String[] {
                "unsetenv_others",
                "prgroup",
                "new_pgroup",
                "rlimit_resourcename",
                "chdir",
                "umask",
                "in",
                "out",
                "err",
                "close_others"
        };

        ALL_SPAWN_OPTIONS = new HashSet<>(Arrays.asList(SPAWN_OPTIONS));
    }

    @Deprecated
    public static void checkExecOptions(IRubyObject options) {
        if (options instanceof RubyHash) {
            RubyHash opts = (RubyHash) options;
            ThreadContext context = opts.getRuntime().getCurrentContext();

            checkValidSpawnOptions(context, opts);
            checkUnsupportedOptions(context, opts, UNSUPPORTED_SPAWN_OPTIONS, "unsupported exec option");
        }
    }

    @Deprecated
    public static void checkSpawnOptions(IRubyObject options) {
        if (options instanceof RubyHash) {
            RubyHash opts = (RubyHash) options;
            ThreadContext context = opts.getRuntime().getCurrentContext();

            checkValidSpawnOptions(context, opts);
            checkUnsupportedOptions(context, opts, UNSUPPORTED_SPAWN_OPTIONS, "unsupported spawn option");
        }
    }

    @Deprecated
    public static void checkPopenOptions(IRubyObject options) {
        if (options instanceof RubyHash) {
            RubyHash opts = (RubyHash) options;
            ThreadContext context = opts.getRuntime().getCurrentContext();

            checkUnsupportedOptions(context, opts, UNSUPPORTED_SPAWN_OPTIONS, "unsupported popen option");
        }
    }

    static void checkUnsupportedOptions(ThreadContext context, RubyHash opts, String[] unsupported, String error) {
        for (String key : unsupported) {
            if (opts.fastARef(asSymbol(context, key)) != null) warn(context, error + ": " + key);
        }
    }

    static void checkValidSpawnOptions(ThreadContext context, RubyHash opts) {
        for (Object opt : opts.directKeySet()) {
            if (opt instanceof RubySymbol) {
                if (!ALL_SPAWN_OPTIONS.contains(((RubySymbol) opt).idString())) {
                    throw argumentError(context, "wrong exec option symbol: " + opt);
                }
            }
            else if (opt instanceof RubyString) {
                if (!ALL_SPAWN_OPTIONS.contains(((RubyString) opt).toString())) {
                    throw argumentError(context, "wrong exec option: " + opt);
                }
            }
        }
    }

    /**
     * Try for around 1s to destroy the child process. This is to work around
     * issues on some JVMs where if you try to destroy the process too quickly
     * it may not be ready and may ignore the destroy. A subsequent waitFor
     * will then hang. This version tries to destroy and call exitValue
     * repeatedly for up to 1000 calls with 1ms delay between iterations, with
     * the intent that the target process ought to be "ready to die" fairly
     * quickly and we don't get stuck in a blocking waitFor call.
     *
     * @param process The process to obliterate
     */
    public static void obliterateProcess(Process process) {
        int i = 0;
        Object waitLock = new Object();
        while (true) {
            // only try 1000 times with a 1ms sleep between, so we don't hang
            // forever on processes that ignore SIGTERM. After that, not much
            // we can do...
            if (i >= 1000) {
                return;
            }

            // attempt to destroy (SIGTERM on UNIX, TerminateProcess on Windows)
            process.destroy();

            try {
                // get the exit value; succeeds if it has terminated, throws
                // IllegalThreadStateException if not.
                process.exitValue();
            } catch (IllegalThreadStateException itse) {
                // increment count and try again after a 1ms sleep
                i += 1;
                synchronized (waitLock) {
                    try {waitLock.wait(1);} catch (InterruptedException ie) {}
                }
                continue;
            }
            // success!
            break;
        }
    }

    public static ModeFlags newModeFlags(Ruby runtime, long mode) {
        return newModeFlags(runtime, (int) mode);
    }

    public static ModeFlags newModeFlags(Ruby runtime, int mode) {
        try {
            return new ModeFlags(mode);
        } catch (InvalidValueException ive) {
            throw runtime.newErrnoEINVALError();
        }
    }

    public static ModeFlags newModeFlags(Ruby runtime, String mode) {
        try {
            return new ModeFlags(mode);
        } catch (InvalidValueException ive) {
            // This is used by File and StringIO, which seem to want an ArgumentError instead of EINVAL
            throw argumentError(runtime.getCurrentContext(), "illegal access mode " + mode);
        }
    }

    public static IOOptions newIOOptions(Ruby runtime, ModeFlags modeFlags) {
        return new IOOptions(modeFlags);
    }

    public static IOOptions newIOOptions(Ruby runtime, long mode) {
        return newIOOptions(runtime, (int) mode);
    }

    public static IOOptions newIOOptions(Ruby runtime, int mode) {
        try {
            ModeFlags modeFlags = new ModeFlags(mode);
            return new IOOptions(modeFlags);
        } catch (InvalidValueException ive) {
            throw runtime.newErrnoEINVALError();
        }
    }

    public static IOOptions newIOOptions(Ruby runtime, String mode) {
        try {
            return new IOOptions(runtime, mode);
        } catch (InvalidValueException ive) {
            // This is used by File and StringIO, which seem to want an ArgumentError instead of EINVAL
            throw argumentError(runtime.getCurrentContext(),"illegal access mode " + mode);
        }
    }

    public static IOOptions newIOOptions(Ruby runtime, IOOptions oldFlags, int orOflags) {
        try {
            return new IOOptions(new ModeFlags(oldFlags.getModeFlags().getFlags() | orOflags));
        } catch (InvalidValueException ive) {
            throw runtime.newErrnoEINVALError();
        }
    }

    @Deprecated
    public IRubyObject readline(ThreadContext context, IRubyObject[] args) {
        return args.length == 0 ? readline(context) : readline(context, args[0]);
    }

    @Override
    public void setEnc2(Encoding enc2) {
        openFile.encs.enc2 = enc2;
    }

    @Override
    public void setEnc(Encoding enc) {
        openFile.encs.enc = enc;
    }

    @Override
    public void setEcflags(int ecflags) {
        openFile.encs.ecflags = ecflags;
    }

    @Override
    public int getEcflags() {
        return openFile.encs.ecflags;
    }

    @Override
    public void setEcopts(IRubyObject ecopts) {
        openFile.encs.ecopts = ecopts;
    }

    @Override
    public IRubyObject getEcopts() {
        return openFile.encs.ecopts;
    }

    @Override
    public void setBOM(boolean bom) {
        openFile.setBOM(bom);
    }

    @Override
    public boolean getBOM() {
        return openFile.isBOM();
    }

    @Override
    public <T> T toJava(Class<T> target) {
        if (target == java.io.InputStream.class) {
            getOpenFile().checkReadable(getRuntime().getCurrentContext());
            return target.cast(getInStream());
        }
        if (target == java.io.OutputStream.class) {
            getOpenFile().checkWritable(getRuntime().getCurrentContext());
            return target.cast(getOutStream());
        }
        return super.toJava(target);
    }

    // MRI: rb_io_ascii8bit_binmode
    protected RubyIO setAscii8bitBinmode() {
        OpenFile fptr;

        fptr = getOpenFileChecked();
        fptr.ascii8bitBinmode(getRuntime());

        return this;
    }

    private static boolean isDash(RubyString str) {
        return str.size() == 1 && str.getByteList().get(0) == '-'; // "-".equals(str.toString());
    }

    public final OpenFile MakeOpenFile() {
        Ruby runtime = getRuntime();
        if (openFile != null) {
            rbIoClose(runtime.getCurrentContext());
            rb_io_fptr_finalize(runtime, openFile);
            openFile = null;
        }
        openFile = new OpenFile(this, runtime.getNil());
        runtime.addInternalFinalizer(openFile);
        return openFile;
    }

    private static int rb_io_fptr_finalize(Ruby runtime, OpenFile fptr) {
        if (fptr == null) return 0;
        fptr.setPath(null);
        if (fptr.fd() != null)
            fptr.cleanup(runtime, true);
        fptr.write_lock = null;
        if (fptr.rbuf.ptr != null) {
            fptr.rbuf.ptr = null;
        }
        if (fptr.wbuf.ptr != null) {
            fptr.wbuf.ptr = null;
        }
        fptr.clearCodeConversion();
        return 1;
    }

    private static IOSites sites(ThreadContext context) {
        return context.sites.IO;
    }

    @Deprecated
    public IRubyObject getline(Ruby runtime, ByteList separator) {
        return getline(runtime.getCurrentContext(), runtime.newString(separator), -1);
    }

    @Deprecated
    public IRubyObject getline(Ruby runtime, ByteList separator, long limit) {
        return getline(runtime.getCurrentContext(), runtime.newString(separator), limit);
    }

    @Deprecated
    public IRubyObject getline(ThreadContext context, ByteList separator) {
        return getline(context, newString(context, separator), -1);
    }

    @Deprecated
    public IRubyObject getline(ThreadContext context, ByteList separator, long limit) {
        return getline(context, newString(context, separator), limit);
    }

    @Deprecated
    public RubyIO(Ruby runtime, STDIO stdio) {
        super(runtime, runtime.getIO());

        RubyIO tmp = null;
        switch (stdio) {
            case IN:
                tmp = prepStdio(runtime, runtime.getIn(), Channels.newChannel(runtime.getIn()), OpenFile.READABLE, runtime.getIO(), "<STDIN>");
                break;
            case OUT:
                tmp = prepStdio(runtime, runtime.getOut(), Channels.newChannel(runtime.getOut()), OpenFile.WRITABLE, runtime.getIO(), "<STDOUT>");
                break;
            case ERR:
                tmp = prepStdio(runtime, runtime.getErr(), Channels.newChannel(runtime.getErr()), OpenFile.WRITABLE | OpenFile.SYNC, runtime.getIO(), "<STDERR>");
                break;
        }

        this.openFile = tmp.openFile;
        tmp.openFile = null;
    }

    @Deprecated
    public RubyIO(Ruby runtime, RubyClass cls, ShellLauncher.POpenProcess process, RubyHash options, IOOptions ioOptions) {
        super(runtime, cls);
        var context = runtime.getCurrentContext();

        ioOptions = updateIOOptionsFromOptions(context, options, ioOptions);

        openFile = MakeOpenFile();

        setupPopen(context, ioOptions.getModeFlags(), process);
    }

    @Deprecated
    public static ModeFlags getIOModes(Ruby runtime, String modesString) {
        return newModeFlags(runtime, modesString);
    }

    @Deprecated
    public static int getIOModesIntFromString(Ruby runtime, String modesString) {
        try {
            return ModeFlags.getOFlagsFromString(modesString);
        } catch (InvalidValueException ive) {
            throw argumentError(runtime.getCurrentContext(), "illegal access mode");
        }
    }

    @Deprecated
    public static IRubyObject writeStatic(ThreadContext context, IRubyObject recv, IRubyObject[] argv, Block unusedBlock) {
        return write(context, recv, argv);
    }

    @Deprecated
    @JRubyMethod(name = "popen3", rest = true, meta = true)
    public static IRubyObject popen3(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        final Ruby runtime = context.runtime;

        // TODO: handle opts
        if (args.length > 0 && args[args.length - 1] instanceof RubyHash) {
            args = ArraySupport.newCopy(args, args.length - 1);
        }

        final POpenTuple tuple = popenSpecial(context, args);
        final long pid = ShellLauncher.getPidFromProcess(tuple.process);

        // array trick to be able to reference enclosing RubyThread
        final RubyThread[] waitThread = new RubyThread[1];
        waitThread[0] = new RubyThread(
                runtime,
                (RubyClass) runtime.getProcess().getConstantAt(context, "WaitThread"), // Process::WaitThread
                new ThreadedRunnable() {

                    volatile Thread javaThread;

                    @Override
                    public Thread getJavaThread() {
                        return javaThread;
                    }

                    @Override
                    public void run() {
                        javaThread = Thread.currentThread();
                        RubyThread rubyThread;
                        // spin a bit until this happens; should almost never spin
                        while ((rubyThread = waitThread[0]) == null) {
                            Thread.yield();
                        }

                        runtime.getThreadService().registerNewThread(rubyThread);

                        rubyThread.op_aset(asSymbol(context, "pid"),  asFixnum(context, pid));

                        try {
                            int exitValue = tuple.process.waitFor();

                            // RubyStatus uses real native status now, so we unshift Java's shifted exit status
                            RubyProcess.RubyStatus status = RubyProcess.RubyStatus.newProcessStatus(
                                    runtime,
                                    exitValue << 8,
                                    pid);

                            rubyThread.cleanTerminate(status);
                        } catch (Throwable t) {
                            rubyThread.exceptionRaised(t);
                        } finally {
                            rubyThread.dispose();
                        }
                    }

                });

        RubyArray yieldArgs = RubyArray.newArrayLight(runtime,
                tuple.output,
                tuple.input,
                tuple.error,
                waitThread[0]);

        if (block.isGiven()) {
            try {
                return block.yield(context, yieldArgs);
            } finally {
                cleanupPOpen(tuple);

                IRubyObject status = waitThread[0].join(context);
                context.setLastExitStatus(status);
            }
        }

        return yieldArgs;
    }

    @Deprecated
    public static IRubyObject popen4(ThreadContext context, IRubyObject recv, IRubyObject[] args, Block block) {
        Ruby runtime = context.runtime;

        try {
            POpenTuple tuple = popenSpecial(context, args);

            RubyArray yieldArgs = RubyArray.newArrayLight(runtime,
                    asFixnum(context, ShellLauncher.getPidFromProcess(tuple.process)),
                    tuple.output,
                    tuple.input,
                    tuple.error);

            if (block.isGiven()) {
                try {
                    return block.yield(context, yieldArgs);
                } finally {
                    cleanupPOpen(tuple);
                    // RubyStatus uses real native status now, so we unshift Java's shifted exit status
                    context.setLastExitStatus(RubyProcess.RubyStatus.newProcessStatus(runtime, tuple.process.waitFor() << 8, ShellLauncher.getPidFromProcess(tuple.process)));
                }
            }
            return yieldArgs;
        } catch (InterruptedException e) {
            throw runtime.newThreadError("unexpected interrupt");
        }
    }

    @Deprecated
    private static void cleanupPOpen(POpenTuple tuple) {
        if (tuple.input.openFile.isOpen()) {
            try {
                tuple.input.close();
            } catch (RaiseException re) {}
        }
        if (tuple.output.openFile.isOpen()) {
            try {
                tuple.output.close();
            } catch (RaiseException re) {}
        }
        if (tuple.error.openFile.isOpen()) {
            try {
                tuple.error.close();
            } catch (RaiseException re) {}
        }
    }

    @Deprecated
    private static class POpenTuple {
        public POpenTuple(RubyIO i, RubyIO o, RubyIO e, Process p) {
            input = i; output = o; error = e; process = p;
        }
        public final RubyIO input;
        public final RubyIO output;
        public final RubyIO error;
        public final Process process;
    }

    @Deprecated
    public static POpenTuple popenSpecial(ThreadContext context, IRubyObject[] args) {
        Ruby runtime = context.runtime;

        try {
            ShellLauncher.POpenProcess process = ShellLauncher.popen3(runtime, args, false);
            RubyIO input = process.getInput() != null ?
                    new RubyIO(runtime, process.getInput()) :
                    new RubyIO(runtime, process.getInputStream());
            RubyIO output = process.getOutput() != null ?
                    new RubyIO(runtime, process.getOutput()) :
                    new RubyIO(runtime, process.getOutputStream());
            RubyIO error = process.getError() != null ?
                    new RubyIO(runtime, process.getError()) :
                    new RubyIO(runtime, process.getErrorStream());

            // ensure the OpenFile knows it's a process; see OpenFile#finalize
            input.getOpenFile().setProcess(process);
            output.getOpenFile().setProcess(process);
            error.getOpenFile().setProcess(process);

            // set all streams as popenSpecial streams, so we don't shut down process prematurely
            input.popenSpecial = true;
            output.popenSpecial = true;
            error.popenSpecial = true;

            // process streams are not seekable
//            input.getOpenFile().getMainStreamSafe().getDescriptor().
//              setCanBeSeekable(false);
//            output.getOpenFile().getMainStreamSafe().getDescriptor().
//              setCanBeSeekable(false);
//            error.getOpenFile().getMainStreamSafe().getDescriptor().
//              setCanBeSeekable(false);

            return new POpenTuple(input, output, error, process);
//        } catch (BadDescriptorException e) {
//            throw runtime.newErrnoEBADFError();
        } catch (IOException e) {
            throw runtime.newIOErrorFromException(e);
        }
    }

    @Deprecated
    public IRubyObject doWriteNonblock(ThreadContext context, IRubyObject[] argv, boolean useException) {
        return write_nonblock(context, argv);
    }

    @Deprecated
    public static IRubyObject select_static(ThreadContext context, Ruby runtime, IRubyObject[] args) {
        return select(context, ioClass(context), args);
    }

    @Deprecated
    public static RubyArray checkExecEnv(ThreadContext context, RubyHash hash) {
        return PopenExecutor.checkExecEnv(context, hash, null);
    }

    @Deprecated
    public static IRubyObject ioOpen(ThreadContext context, IRubyObject filename, IRubyObject vmode, IRubyObject vperm, IRubyObject opt) {
        return ioOpen(context, ioClass(context), filename, vmode, vperm, opt);
    }

    @Deprecated
    public IRubyObject write_nonblock(ThreadContext context, IRubyObject[] argv) {
        switch (argv.length) {
            case 1:
                return write_nonblock(context, argv[0]);
            case 2:
                return write_nonblock(context, argv[0], argv[1]);
            default:
                throw argumentError(context, argv.length, 1, 2);
        }
    }

    @Deprecated
    public IRubyObject read_nonblock(ThreadContext context, IRubyObject[] args) {
        Arity.checkArgumentCount(context, args, 1, 3);

        boolean exception = ArgsUtil.extractKeywordArg(context, "exception", args) != context.fals;
        return doReadNonblock(context, args, exception);
    }

    @Deprecated
    public IRubyObject readpartial(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 2);

        // ruby bug 11885
        if (argc == 2) {
            args[1] = args[1].convertToString();
        }

        IRubyObject value = getPartial(context, args, false, false);

        if (value.isNil()) {
            throw context.runtime.newEOFError();
        }

        return value;
    }

    @Deprecated
    public IRubyObject sysread(ThreadContext context, IRubyObject[] args) {
        int argc = Arity.checkArgumentCount(context, args, 1, 2);

        final Ruby runtime = context.runtime;

        IRubyObject _length = argc >= 1 ? args[0] : context.nil;
        IRubyObject _str = argc >= 2 ? args[1] : context.nil;

        return sysreadCommon(context, runtime, _length, _str);
    }

    @Deprecated
    public static IRubyObject pipe(ThreadContext context, IRubyObject klass, IRubyObject[] argv, Block block) {
        switch (argv.length) {
            case 0:
                return pipe(context, klass, block);
            case 1:
                return pipe(context, klass, argv[0], block);
            case 2:
                return pipe(context, klass, argv[0], argv[1], block);
            case 3:
                return pipe(context, klass, argv[0], argv[1], argv[2], block);
            default:
                throw argumentError(context, argv.length, 0, 3);
        }
    }

    protected OpenFile openFile;

    /**
     * If the stream is being used for popen, we don't want to destroy the process
     * when we close the stream.
     */
    protected boolean popenSpecial;
}
