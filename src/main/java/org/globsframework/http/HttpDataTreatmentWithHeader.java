package org.globsframework.http;

import org.globsframework.metamodel.annotations.ArgName;
import org.globsframework.model.Glob;

import java.util.concurrent.CompletableFuture;

public interface HttpDataTreatmentWithHeader {

    CompletableFuture<HttpOutputData> consume(HttpInputData body,
                                              @ArgName("url") Glob url,
                                              @ArgName("queryParameters") Glob queryParameters,
                                              @ArgName("headers") Glob headerType) throws Exception;
}
