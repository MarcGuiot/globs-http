package org.globsframework.http;

import org.apache.http.*;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.nio.entity.NFileEntity;
import org.apache.http.nio.protocol.BasicAsyncResponseProducer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.protocol.HttpContext;
import org.globsframework.http.model.DataAnnotationType;
import org.globsframework.http.model.StatusCodeAnnotationType;
import org.globsframework.json.GSonUtils;
import org.globsframework.metamodel.Field;
import org.globsframework.metamodel.GlobType;
import org.globsframework.metamodel.GlobTypeLoaderFactory;
import org.globsframework.metamodel.annotations.FieldNameAnnotationType;
import org.globsframework.metamodel.fields.GlobArrayField;
import org.globsframework.metamodel.fields.GlobField;
import org.globsframework.metamodel.fields.IntegerField;
import org.globsframework.model.Glob;
import org.globsframework.model.MutableGlob;
import org.globsframework.utils.Files;
import org.globsframework.utils.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

import static org.apache.http.HttpStatus.*;

/**
 * HttpAsyncRequestHandler for globs framework.
 */
public class GlobHttpRequestHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobHttpRequestHandler.class);

    public static final String APPLICATION_JSON = "application/json";
    public static final String RECEIVED_MSG = "{} : received for {} {} : {}";
    public static final String RESPONSE_MSG = "{} : responded for {} {} : {} : {}";

    private final UrlMatcher urlMatcher;
    private String serverInfo;
    private final HttpReceiver httpReceiver;
    private HttpHandler onPost;
    private HttpHandler onPut;
    private HttpHandler onPatch;
    private HttpHandler onDelete;
    private HttpHandler onGet;
    private HttpHandler onOption;

    public GlobHttpRequestHandler(String serverInfo, HttpReceiver httpReceiver) {
        this.serverInfo = serverInfo;
        this.httpReceiver = httpReceiver;
        this.urlMatcher = DefaultUrlMatcher.create(httpReceiver.getUrlType(), httpReceiver.getUrl());
        for (HttpOperation operation : httpReceiver.getOperations()) {
            switch (operation.verb()) {
                case post -> onPost = new HttpHandler(serverInfo, operation);
                case put -> onPut = new HttpHandler(serverInfo, operation);
                case patch -> onPatch = new HttpHandler(serverInfo, operation);
                case delete -> onDelete = new HttpHandler(serverInfo, operation);
                case get -> onGet = new HttpHandler(serverInfo, operation);
                case option -> onOption = new HttpHandler(serverInfo, operation);
                default -> throw new IllegalStateException("Unexpected value: " + operation.verb());
            }
        }
    }

    /**
     * Creates a regular expression based on the url pattern.
     * For example /foo/{A}/bar becomes /foo/\*\/bar
     *
     * @return a regexp of the url pattern
     */
    public Collection<String> createRegExp() {
        String[] split = httpReceiver.getUrl().split("/");
        List<String> matcher = new ArrayList<>();
        StringBuilder stringBuilder = new StringBuilder();
        for (String s : split) {
            if (!s.isEmpty()) {
                if (s.startsWith("{") && s.endsWith("}")) {
                    matcher.add(null);
                } else {
                    matcher.add(s);
                    stringBuilder.append(s);
                }
            }
        }
        LOGGER.debug(serverInfo + " regex: {}", stringBuilder);
        return matcher;
    }

    public void handle(String[] path, String paramStr, HttpRequest httpRequest, HttpAsyncExchange httpAsyncExchange,
                       HttpContext httpContext) throws HttpException {
        RequestLine requestLine = httpRequest.getRequestLine();
        String requestMethod = getRequestMethod(requestLine);
        String requestUri = requestLine.getUri();

        LOGGER.info("{} : receiving {} {}", serverInfo, requestMethod, requestUri);
        final HttpResponse response = httpAsyncExchange.getResponse();

        try {
            switch (Objects.requireNonNull(requestMethod)) {
                case HttpDelete.METHOD_NAME -> treatOp(path, paramStr, httpAsyncExchange, httpRequest, response, onDelete);
                case HttpPost.METHOD_NAME -> treatOp(path, paramStr, httpAsyncExchange, httpRequest, response, onPost);
                case HttpPut.METHOD_NAME -> treatOp(path, paramStr, httpAsyncExchange, httpRequest, response, onPut);
                case HttpPatch.METHOD_NAME -> treatOp(path, paramStr, httpAsyncExchange, httpRequest, response, onPatch);
                case HttpGet.METHOD_NAME -> treatOp(path, paramStr, httpAsyncExchange, httpRequest, response, onGet);
                case HttpOptions.METHOD_NAME -> {
                    response.setStatusCode(SC_OK);
                    this.httpReceiver.headers(response::addHeader);
                    httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
                }
                default -> {
                    response.setStatusCode(SC_FORBIDDEN);
                    httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
                }
            }
        } catch (Exception e) {
            logError(httpRequest, SC_FORBIDDEN, e.getMessage(), e);
            LOGGER.error(serverInfo + " : request error for " + requestMethod + " " + requestUri, e);
            response.setStatusCode(SC_FORBIDDEN);
            httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
        }

        LOGGER.info("{} : done {} {}", serverInfo, requestMethod, requestUri);
    }

    private void treatOp(String[] path, String paramStr, HttpAsyncExchange httpAsyncExchange,
                         HttpRequest request, HttpResponse response, HttpHandler httpHandler) throws Exception {
        RequestLine requestLine = request.getRequestLine();
        String requestMethod = getRequestMethod(requestLine);
        String requestUri = requestLine.getUri();

        if (httpHandler == null) {
            LOGGER.error("{} : received unexpected request {} {} : {}", serverInfo, requestMethod, requestUri, SC_FORBIDDEN);
            response.setStatusCode(SC_FORBIDDEN);
            httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
        } else if (request instanceof HttpEntityEnclosingRequest) { // DELETE, POST, PUT, PATCH
            treatOpWithRequestBody(path, paramStr, httpAsyncExchange, request, response, httpHandler);
        } else if (request instanceof BasicHttpRequest) { // GET
            treatOpWithoutRequestBody(path, paramStr, httpAsyncExchange, request, response, httpHandler);
        } else {
            LOGGER.error("{} : unexpected type {} for {} {} : {}", serverInfo, request.getClass().getName(),
                    requestMethod, requestUri, SC_INTERNAL_SERVER_ERROR);
            response.setStatusCode(SC_INTERNAL_SERVER_ERROR);
            httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
        }
    }

    private void treatOpWithRequestBody(String[] path, String paramStr, HttpAsyncExchange httpAsyncExchange,
                                        HttpRequest request, HttpResponse response, HttpHandler httpHandler) throws Exception {
        HttpOperation operation = httpHandler.operation;
        GlobType bodyGlobType = operation.getBodyType();
        HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
        Glob data;
        Runnable postOp = () -> {};

        if (bodyGlobType == GlobHttpContent.TYPE) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Files.copyStream(entity.getContent(), outputStream);
            data = GlobHttpContent.TYPE.instantiate()
                    .set(GlobHttpContent.content, outputStream.toByteArray());
            logRequestData(request, "[byte array]");
        } else if (bodyGlobType == GlobFile.TYPE) {
            File tempFile = File.createTempFile("http", ".data");
            try (FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                Files.copyStream(entity.getContent(), outputStream);
            }

            data = GlobFile.TYPE.instantiate()
                    .set(GlobFile.file, tempFile.getAbsolutePath());
            postOp = () -> {
                if (tempFile.exists() && !tempFile.delete()) {
                    RequestLine requestLine = request.getRequestLine();
                    String requestMethod = getRequestMethod(requestLine);
                    String requestUri = requestLine.getUri();

                    LOGGER.error("{} : failed to delete tmp file {} for {} {}", serverInfo,
                            tempFile.getAbsolutePath(), requestMethod, requestUri);
                }
            };
            logRequestData(request, "[file data]");
        } else {
            //find mimetype (if xml => produce xml)
//                    Arrays.stream(entity.getContentType().getElements())
//                            .filter(headerElement -> headerElement.getName().equals())
            String str = Files.read(entity.getContent(), StandardCharsets.UTF_8);
            data = (Strings.isNullOrEmpty(str) || bodyGlobType == null) ? null : GSonUtils.decode(str, bodyGlobType);
            String strToLog = operation.hasSensitiveData() && data != null ? GSonUtils.encodeHidSensitiveData(data) : str;

            logRequestData(request, strToLog);
        }
        consumeOp(path, paramStr, httpAsyncExchange, request, response, httpHandler, data, postOp);
    }

    private void treatOpWithoutRequestBody(String[] path, String paramStr, HttpAsyncExchange httpAsyncExchange,
                                           HttpRequest request, HttpResponse response, HttpHandler httpHandler) throws Exception {
        consumeOp(path, paramStr, httpAsyncExchange, request, response, httpHandler, null, () -> {});
    }

    private void consumeOp(String[] path, String paramStr, HttpAsyncExchange httpAsyncExchange,
                           HttpRequest request, HttpResponse response, HttpHandler httpHandler, Glob data, Runnable postOp) throws Exception {
        HttpOperation operation = httpHandler.operation;
        Glob url = urlMatcher.parse(path);
        Glob queryParam = httpHandler.teatParam(paramStr);
        try {
            CompletableFuture<Glob> consumerResult = operation.consume(data, url, queryParam);

            if (consumerResult != null) {
                consumerResult.whenComplete((glob, throwable) -> {
                    if (glob != null) {
                        consumeGlob(request, response, glob, operation.hasSensitiveData());
                    } else if (throwable != null) {
                        consumeThrowable(request, response, throwable);
                    } else { // null response glob & throwable
                        response.setStatusCode(SC_NO_CONTENT);
                        logResponseData(request, SC_NO_CONTENT, "[no content]");
                    }
                    operation.headers(response::addHeader);
                    httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
                    postOp.run();
                });
            } else {
                response.setStatusCode(SC_NO_CONTENT);
                operation.headers(response::addHeader);
                httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
                logResponseData(request, SC_NO_CONTENT, "[no content]");
                postOp.run();
            }
        } catch (Exception e) {
            consumeThrowable(request, response, e);
            operation.headers(response::addHeader);
            httpAsyncExchange.submitResponse(new BasicAsyncResponseProducer(response));
            postOp.run();
        }
    }

    private void consumeGlob(HttpRequest request, HttpResponse response, Glob glob, boolean hasSensitiveData) {
        GlobType globType = glob.getType();

        if (globType == GlobHttpContent.TYPE) {
            consumeGlobHttpContent(request, response, glob);
        } else if (globType == GlobFile.TYPE) {
            consumeFileGlob(request, response, glob);
        } else {
            consumeNormalGlob(request, response, glob, hasSensitiveData);
        }
    }

    private void consumeGlobHttpContent(HttpRequest request, HttpResponse response, Glob glob) {
        response.setEntity(new ByteArrayEntity(glob.get(GlobHttpContent.content), createContentTypeFromGlobHttpContent(glob)));
        response.setStatusCode(SC_OK);
        logResponseData(request, SC_OK, "[byte array]");
    }

    private void consumeFileGlob(HttpRequest request, HttpResponse response, Glob glob) {
        NFileEntity returnEntity;
        final File file = new File(glob.get(GlobFile.file));
        if (glob.get(GlobFile.removeWhenDelivered, !LOGGER.isTraceEnabled())) {
            returnEntity = new NFileEntity(file, createContentTypeFromGlobFile(glob)) {
                public void close() throws IOException {
                    super.close();
                    if (!file.delete() && file.exists()) {
                        RequestLine requestLine = request.getRequestLine();
                        String requestMethod = getRequestMethod(requestLine);
                        String requestUri = requestLine.getUri();

                        LOGGER.error("{} : failed to delete file {} for {} {}", serverInfo,
                                file.getAbsolutePath(), requestMethod, requestUri);
                    }
                }
            };
        } else {
            returnEntity = new NFileEntity(file, createContentTypeFromGlobFile(glob));
        }
        response.setEntity(returnEntity);
        response.setStatusCode(SC_OK);
        logResponseData(request, SC_OK, "[file data]");
    }

    private void consumeNormalGlob(HttpRequest request, HttpResponse response, Glob glob, boolean hasSensitiveData) {
        GlobType globType = glob.getType();
        Field fieldWithStatusCode = globType.findFieldWithAnnotation(StatusCodeAnnotationType.UNIQUE_KEY);
        Field fieldWithData = globType.findFieldWithAnnotation(DataAnnotationType.UNIQUE_KEY);

        Integer statusCode;
        String strData;
        String strDataToLog;
        if (fieldWithStatusCode instanceof IntegerField statusField
                && (fieldWithData instanceof GlobField || fieldWithData instanceof GlobArrayField)) {
            statusCode = glob.get(statusField);

            if (fieldWithData instanceof GlobField globDataField) {
                Glob data = glob.get(globDataField);
                strData = data != null ? GSonUtils.encode(data, false) : null;
                strDataToLog = hasSensitiveData && data != null ? GSonUtils.encodeHidSensitiveData(data) : strData;
            } else {
                Glob[] data = glob.get((GlobArrayField) fieldWithData);
                strData = data != null ? GSonUtils.encode(data, false) : null;
                strDataToLog = hasSensitiveData && data != null ? GSonUtils.encodeHidSensitiveData(data) : strData;
            }
        } else {
            statusCode = SC_OK;
            strData = GSonUtils.encode(glob, false);
            strDataToLog = hasSensitiveData ? GSonUtils.encodeHidSensitiveData(glob) : strData;
        }

        response.setStatusCode(statusCode);

        if (Optional.ofNullable(request.getFirstHeader(HttpHeaders.ACCEPT_ENCODING))
                .map(NameValuePair::getValue)
                .map(value -> value.contains("gzip"))
                .orElse(false)) {
            try {
                response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
                response.setEntity(newCompressedDataEntity(strData));
                logResponseData(request, statusCode, "(gzip) " + strDataToLog);
            } catch (IOException e) {
                RequestLine requestLine = request.getRequestLine();
                String requestMethod = getRequestMethod(requestLine);
                String requestUri = requestLine.getUri();
                
                LOGGER.error(serverInfo + " : io error bug on GZIP for " + requestMethod + " " + requestUri, e);
                response.setStatusCode(SC_METHOD_FAILURE);
            }
        } else {
            response.setEntity(new StringEntity(strData, ContentType.APPLICATION_JSON));
            logResponseData(request, statusCode, strDataToLog);
        }
    }

    private HttpEntity newCompressedDataEntity(String strData) throws IOException {
        try (ArrayOutputInputStream out = new ArrayOutputInputStream()) {
            try (OutputStreamWriter writer = new OutputStreamWriter(new GZIPOutputStream(out), StandardCharsets.UTF_8)) {
                writer.write(strData);
            }

            return new ByteArrayEntity(out.toByteArray(), ContentType.APPLICATION_JSON);
        }
    }

    private void logRequestData(HttpRequest request, String str) {
        RequestLine requestLine = request.getRequestLine();
        String requestMethod = getRequestMethod(requestLine);
        String requestUri = requestLine.getUri();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(RECEIVED_MSG, serverInfo, requestMethod, requestUri, str);
        } else {
            LOGGER.info(RECEIVED_MSG, serverInfo, requestMethod, requestUri, str.substring(0, Math.min(10000, str.length())));
        }
    }

    private void logResponseData(HttpRequest request, int statusCode, String str) {
        RequestLine requestLine = request.getRequestLine();
        String requestMethod = getRequestMethod(requestLine);
        String requestUri = requestLine.getUri();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(RESPONSE_MSG, serverInfo, requestMethod, requestUri, statusCode, str);
        } else {
            LOGGER.info(RESPONSE_MSG, serverInfo, requestMethod, requestUri, statusCode, str.substring(0, Math.min(10000, str.length())));
        }
    }

    private void logError(HttpRequest request, int statusCode, String message, Throwable t) {
        RequestLine requestLine = request.getRequestLine();
        String requestMethod = getRequestMethod(requestLine);
        String requestUri = requestLine.getUri();

        LOGGER.error(serverInfo + " : request failed for " + requestMethod + " " + requestUri
                + " : " + statusCode + " : " + message, t);
    }

    private void consumeThrowable(HttpRequest request, HttpResponse response, Throwable throwable) {
        int statusCode;
        String message;
        String reasonPhrase = null;
        if (throwable instanceof HttpException e) {
            statusCode = e.code;
            reasonPhrase = e.message;
            message = e.message;
        } else if (throwable instanceof HttpExceptionWithContent e) {
            statusCode = e.code;
            message = GSonUtils.encode(e.message, false);
            response.setEntity(new StringEntity(message, ContentType.APPLICATION_JSON));
        } else {
            statusCode = SC_INTERNAL_SERVER_ERROR;
            message = throwable.getMessage();
        }

        response.setStatusCode(statusCode);
        response.setReasonPhrase(reasonPhrase);
        logError(request, statusCode, message, throwable);
    }

    private String getRequestMethod(RequestLine requestLine) {
        String method = requestLine.getMethod();

        return method != null ? method.toUpperCase(Locale.ROOT) : null;
    }

    private ContentType createContentTypeFromGlobHttpContent(Glob glob) {
        String charsetName = glob.get(GlobHttpContent.charset);
        Charset charset = charsetName != null ? Charset.forName(charsetName) : null;

        return ContentType.create(glob.get(GlobHttpContent.mimeType, "application/octet-stream"), charset);
    }

    private ContentType createContentTypeFromGlobFile(Glob glob) {
        return ContentType.create(glob.get(GlobFile.mimeType, APPLICATION_JSON), StandardCharsets.UTF_8);
    }

    public static DefaultHttpOperation post(HttpTreatment function, GlobType bodyType) {
        return post(function, bodyType, null);
    }

    public static DefaultHttpOperation post(HttpTreatment function, GlobType bodyType, GlobType queryType) {
        return new DefaultHttpOperation(HttpOp.post, bodyType, queryType, function);
    }

    public static DefaultHttpOperation get(HttpTreatment function) {
        return get(function, null);
    }

    public static DefaultHttpOperation get(HttpTreatment function, GlobType queryType) {
        return new DefaultHttpOperation(HttpOp.get, null, queryType, function);
    }

    public static DefaultHttpOperation delete(HttpTreatment function) {
        return delete(function, null);
    }

    public static DefaultHttpOperation delete(HttpTreatment function, GlobType queryType) {
        return new DefaultHttpOperation(HttpOp.delete, null, queryType, function);
    }

    public boolean hasWildcardAtEnd() {
        return urlMatcher.withWildCard();
    }

    public interface ParamProcessor {
        Glob treat(String queryParams);
    }

    public static class HttpHandler {
        private final String serverInfo;
        private final HttpOperation operation;
        private final ParamProcessor paramProcessor;

        public HttpHandler(String serverInfo, HttpOperation operation) {
            this.serverInfo = serverInfo;
            this.operation = operation;
            paramProcessor = operation.getQueryParamType() == null ? allHeaders -> null : new DefaultParamProcessor(this.serverInfo, operation.getQueryParamType());
        }

        public Glob teatParam(String queryParam) {
            return paramProcessor.treat(queryParam);
        }
    }

    public static class DefaultParamProcessor implements ParamProcessor {
        private final String serverInfo;
        GlobType paramType;
        Map<String, GlobHttpUtils.FromStringConverter> converterMap = new HashMap<>();

        public DefaultParamProcessor(String serverInfo, GlobType paramType) {
            this.serverInfo = serverInfo;
            this.paramType = paramType;
            for (Field field : paramType.getFields()) {
                converterMap.put(FieldNameAnnotationType.getName(field), GlobHttpUtils.createConverter(field, ","));
            }
        }

        public Glob treat(String queryParams) {
            if (Strings.isNotEmpty(queryParams)) {
                MutableGlob instantiate = paramType.instantiate();
                List<NameValuePair> parse = URLEncodedUtils.parse(queryParams, StandardCharsets.UTF_8);
                for (NameValuePair nameValuePair : parse) {
                    GlobHttpUtils.FromStringConverter fromStringConverter = converterMap.get(nameValuePair.getName());
                    if (fromStringConverter != null) {
                        fromStringConverter.convert(instantiate, nameValuePair.getValue());
                    } else {
                        LOGGER.error("{} : unexpected param {}", serverInfo, nameValuePair.getName());
                    }
                }
                return instantiate;
            }
            return null;
        }
    }

    public static class EmptyType {
        public static GlobType TYPE;

        static {
            GlobTypeLoaderFactory.create(EmptyType.class).load();
        }
    }

    private static class ArrayOutputInputStream extends ByteArrayOutputStream {
        int at = 0;

        InputStream getInputStream() {
            return new InputStream() {
                public int read() throws IOException {
                    return at < count ? buf[at++] : -1;
                }
            };
        }
    }
}
