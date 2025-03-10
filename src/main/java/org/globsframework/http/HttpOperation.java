package org.globsframework.http;

import org.globsframework.core.metamodel.GlobType;
import org.globsframework.core.model.Glob;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface HttpOperation {

    void withExecutor(Executor executor);

    String getComment();

    HttpOp verb();

    CompletableFuture<HttpOutputData> consume(HttpInputData data, Glob url, Glob queryParameters, Glob header) throws Exception;

    GlobType getBodyType();

    GlobType getQueryParamType();

    GlobType getReturnType();

    String[] getTags();

    void headers(HeaderConsumer headerConsumer);

    boolean hasSensitiveData();

    GlobType getHeaderType();

    interface HeaderConsumer {
        void push(String name, String value);
    }

    Executor getExecutor();
}
