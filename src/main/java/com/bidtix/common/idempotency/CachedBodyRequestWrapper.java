package com.bidtix.common.idempotency;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.jspecify.annotations.NonNull;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;

/** A request whose body was read once (to hash it) and can be read again by the controller. */
final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return in.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException("Async reads are not supported");
            }

            @Override
            public int read() {
                return in.read();
            }

            @Override
            public int read(byte @NonNull [] buffer, int off, int len) {
                return in.read(buffer, off, len);
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        java.nio.charset.Charset charset = encoding == null
                ? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
}
