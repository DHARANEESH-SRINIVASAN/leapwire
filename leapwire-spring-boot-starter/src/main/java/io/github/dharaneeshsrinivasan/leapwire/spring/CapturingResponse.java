package io.github.dharaneeshsrinivasan.leapwire.spring;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class CapturingResponse extends HttpServletResponseWrapper {

    private enum Mode { TEE, CAPTURE, PASS }

    private final int limit;
    private Mode mode = Mode.TEE;
    private ByteArrayOutputStream copy = new ByteArrayOutputStream();
    private boolean started;
    private ServletOutputStream target;
    private Stream stream;
    private PrintWriter writer;

    CapturingResponse(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
    }

    boolean capture() {
        if (mode != Mode.TEE || started || isCommitted()) {
            return false;
        }
        mode = Mode.CAPTURE;
        return true;
    }

    boolean capturing() {
        return mode == Mode.CAPTURE;
    }

    byte[] body() {
        return copy == null ? null : copy.toByteArray();
    }

    void finish() {
        if (writer != null) {
            writer.flush();
        }
    }

    void release() throws IOException {
        finish();
        Mode previous = mode;
        mode = Mode.PASS;
        if (previous == Mode.CAPTURE && copy != null && copy.size() > 0) {
            copy.writeTo(target());
        }
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (stream == null) {
            stream = new Stream();
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        finish();
        if (mode != Mode.CAPTURE) {
            super.flushBuffer();
        }
    }

    @Override
    public void resetBuffer() {
        super.resetBuffer();
        if (copy != null) {
            copy.reset();
        }
    }

    @Override
    public void reset() {
        super.reset();
        if (copy != null) {
            copy.reset();
        }
        writer = null;
    }

    private ServletOutputStream target() throws IOException {
        if (target == null) {
            target = super.getOutputStream();
        }
        return target;
    }

    private void write(byte[] bytes, int offset, int length) throws IOException {
        if (length <= 0) {
            return;
        }
        if (!started) {
            started = true;
            if (getStatus() != HttpServletResponse.SC_OK || !Json.isJson(getContentType())) {
                copy = null;
                if (mode == Mode.CAPTURE) {
                    mode = Mode.PASS;
                }
            }
        }
        if (mode == Mode.CAPTURE) {
            if (copy.size() + length <= limit) {
                copy.write(bytes, offset, length);
                return;
            }
            mode = Mode.PASS;
            copy.writeTo(target());
            copy = null;
        }
        target().write(bytes, offset, length);
        if (copy != null && mode == Mode.TEE) {
            if (copy.size() + length <= limit) {
                copy.write(bytes, offset, length);
            } else {
                copy = null;
            }
        }
    }

    private final class Stream extends ServletOutputStream {

        @Override
        public void write(int b) throws IOException {
            CapturingResponse.this.write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            CapturingResponse.this.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            if (mode != Mode.CAPTURE) {
                target().flush();
            }
        }

        @Override
        public void close() throws IOException {
            if (mode != Mode.CAPTURE) {
                target().close();
            }
        }

        @Override
        public boolean isReady() {
            try {
                return mode == Mode.CAPTURE || target().isReady();
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            try {
                target().setWriteListener(listener);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
