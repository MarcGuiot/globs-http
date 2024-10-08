package org.globsframework.http;

import org.globsframework.core.model.Glob;
import org.globsframework.json.GSonUtils;

public class HttpExceptionWithContent extends HttpException {
    private final Glob content;

    public HttpExceptionWithContent(int code, Glob content) {
        super(code, null);
        this.content = content;
    }

    public Glob getContent() {
        return content;
    }

    public String getOriginalMessage() {
        return content != null ? GSonUtils.encode(content, false) : null;
    }

}
