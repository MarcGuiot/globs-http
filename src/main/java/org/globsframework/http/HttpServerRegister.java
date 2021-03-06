package org.globsframework.http;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.impl.nio.bootstrap.HttpServer;
import org.apache.http.impl.nio.bootstrap.ServerBootstrap;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.protocol.HttpContext;
import org.globsframework.http.openapi.model.*;
import org.globsframework.json.GSonUtils;
import org.globsframework.json.annottations.IsJsonContentAnnotation;
import org.globsframework.metamodel.Field;
import org.globsframework.metamodel.GlobType;
import org.globsframework.metamodel.GlobTypeLoaderFactory;
import org.globsframework.metamodel.annotations.CommentType;
import org.globsframework.metamodel.fields.*;
import org.globsframework.model.Glob;
import org.globsframework.model.MutableGlob;
import org.globsframework.utils.Ref;
import org.globsframework.utils.Strings;
import org.globsframework.utils.collections.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class HttpServerRegister {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerRegister.class);

    private static final String DOUBLE_STR = "double";
    private static final String NUMBER_STR = "number";
    private static final String ARRAY_STR = "array";
    private static final String BIG_DECIMAL_STR = "big-decimal";
    private static final String STRING_STR = "string";

    final Map<String, Verb> verbMap = new HashMap<>();
    private final String serverInfo;
    private Glob openApiDoc;

    public HttpServerRegister(String serverInfo) {
        this.serverInfo = serverInfo;
    }

    public Verb register(String url, GlobType queryUrl) {
        var current = verbMap.get(url);
        if (current == null) {
            var verb = new Verb(url, queryUrl);
            verbMap.put(url, verb);
            return verb;
        } else {
            if (current.queryUrl != queryUrl) {
                throw new RuntimeException("Same query Type is expected for same url on different verb (" + url + ")");
            }
        }
        return current;
    }

    public void registerOpenApi() {
        register("/api", null)
                .get(null, new HttpTreatment() {
                    public CompletableFuture<Glob> consume(Glob body, Glob url, Glob queryParameters) throws Exception {
                        return CompletableFuture.completedFuture(openApiDoc);
                    }
                });
    }

    public Glob createOpenApiDoc(int port) {
        Map<GlobType, Glob> schemas = new HashMap<>();
        List<Glob> paths = new ArrayList<>();
        for (Map.Entry<String, Verb> stringVerbEntry : verbMap.entrySet()) {
            var verb = stringVerbEntry.getValue();
            MutableGlob path = OpenApiPath.TYPE.instantiate();
            paths.add(path);
            path.set(OpenApiPath.name, verb.url);
            for (HttpOperation operation : stringVerbEntry.getValue().operations) {
                MutableGlob desc = OpenApiPathDsc.TYPE.instantiate();
                String comment = operation.getComment();
                if (comment != null) {
                    desc.set(OpenApiPathDsc.description, comment);
                }

                List<Glob> parameters = new ArrayList<>();
                if (verb.queryUrl != null) {
                    for (Field field : verb.queryUrl.getFields()) {
                        var apiFieldVisitor = new OpenApiFieldVisitor(schemas);
                        var openApiFieldVisitor = field.safeVisit(apiFieldVisitor);
                        parameters.add(OpenApiParameter.TYPE.instantiate()
                                .set(OpenApiParameter.in, "path")
                                .set(OpenApiParameter.name, field.getName())
                                .set(OpenApiParameter.required, true)
                                .set(OpenApiParameter.schema, openApiFieldVisitor.schema));
                    }
                }
                GlobType queryParamType = operation.getQueryParamType();
                if (queryParamType != null) {
                    for (Field field : queryParamType.getFields()) {
                        OpenApiFieldVisitor openApiFieldVisitor = field.safeVisit(new OpenApiFieldVisitor(schemas));
                        parameters.add(OpenApiParameter.TYPE.instantiate()
                                .set(OpenApiParameter.in, "query")
                                .set(OpenApiParameter.name, field.getName())
                                .set(OpenApiParameter.required, true)
                                .set(OpenApiParameter.schema, openApiFieldVisitor.schema));
                    }
                }

                GlobType bodyType = operation.getBodyType();
                if (bodyType != null) {
                    desc.set(OpenApiPathDsc.requestBody, OpenApiRequestBody.TYPE.instantiate()
                            .set(OpenApiRequestBody.content, new Glob[]{OpenApiBodyMimeType.TYPE.instantiate()
                                    .set(OpenApiBodyMimeType.mimeType, "application/json")
                                    .set(OpenApiBodyMimeType.schema, buildSchema(bodyType, schemas))}));
                }
                GlobType returnType = operation.getReturnType();
                if (returnType == null) {
                    desc.set(OpenApiPathDsc.responses, new Glob[]{OpenApiResponses.TYPE
                            .instantiate()
                            .set(OpenApiResponses.description, "None")
                            .set(OpenApiResponses.code, "200")});
                } else {
                    desc.set(OpenApiPathDsc.responses, new Glob[]{OpenApiResponses.TYPE.instantiate()
                            .set(OpenApiResponses.code, "200")
                            .set(OpenApiResponses.description,
                                    returnType.findOptAnnotation(CommentType.UNIQUE_KEY)
                                            .map(CommentType.VALUE).orElse("None"))
                            .set(OpenApiResponses.content, new Glob[]{
                            OpenApiBodyMimeType.TYPE.instantiate()
                                    .set(OpenApiBodyMimeType.mimeType, "application/json")
                                    .set(OpenApiBodyMimeType.schema, buildSchema(returnType, schemas))})
                    });
                }

                desc.set(OpenApiPathDsc.parameters, parameters.toArray(Glob[]::new));
                switch (operation.verb()) {
                    case post:
                        path.set(OpenApiPath.post, desc);
                        break;
                    case put:
                        path.set(OpenApiPath.put, desc);
                        break;
                    case delete:
                        path.set(OpenApiPath.delete, desc);
                        break;
                    case get:
                        path.set(OpenApiPath.get, desc);
                        break;
                }
            }
        }

        return OpenApiType.TYPE.instantiate()
                .set(OpenApiType.openAPIVersion, "3.0.1")
                .set(OpenApiType.info, OpenApiInfo.TYPE.instantiate()
                        .set(OpenApiInfo.description, serverInfo)
                        .set(OpenApiInfo.title, serverInfo)
                        .set(OpenApiInfo.version, "1.0")
                )
                .set(OpenApiType.components, OpenApiComponents.TYPE.instantiate()
                        .set(OpenApiComponents.schemas, schemas.values().toArray(Glob[]::new)))
                .set(OpenApiType.servers, new Glob[]{OpenApiServers.TYPE.instantiate()
                        .set(OpenApiServers.url, "http://localhost:" + port)})
                .set(OpenApiType.paths, paths.toArray(Glob[]::new));
    }

    private MutableGlob buildSchema(GlobType bodyType, Map<GlobType, Glob> schemas) {
        if (!schemas.containsKey(bodyType)) {
            MutableGlob schema = OpenApiSchemaProperty.TYPE.instantiate();
            schemas.put(bodyType, schema);
            schema.set(OpenApiSchemaProperty.name, bodyType.getName());
            schema.set(OpenApiSchemaProperty.type, "object");
            List<Glob> param = new ArrayList<>();
            for (Field field : bodyType.getFields()) {
                param.add(subType(field, schemas));
            }
            schema.set(OpenApiSchemaProperty.properties, param.toArray(Glob[]::new));
        }
        return OpenApiSchemaProperty.TYPE.instantiate()
                .set(OpenApiSchemaProperty.ref, "#/components/schemas/" + bodyType.getName());
    }

    private Glob subType(Field field, Map<GlobType, Glob> schemas) {
        final Ref<Glob> p = new Ref<>();
        field.safeVisit(new FieldVisitor.AbstractWithErrorVisitor() {

            @Override
            public void visitDouble(DoubleField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, DOUBLE_STR)
                        .set(OpenApiSchemaProperty.type, NUMBER_STR);
                p.set(instantiate);
            }

            @Override
            public void visitDoubleArray(DoubleArrayField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                OpenApiSchemaProperty.TYPE.instantiate()
                                        .set(OpenApiSchemaProperty.format, DOUBLE_STR)
                                        .set(OpenApiSchemaProperty.type, NUMBER_STR));
                p.set(instantiate);
            }

            @Override
            public void visitBigDecimal(BigDecimalField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, BIG_DECIMAL_STR)
                        .set(OpenApiSchemaProperty.type, STRING_STR);
                p.set(instantiate);
            }

            @Override
            public void visitBigDecimalArray(BigDecimalArrayField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                OpenApiSchemaProperty.TYPE.instantiate()
                                        .set(OpenApiSchemaProperty.format, BIG_DECIMAL_STR)
                                        .set(OpenApiSchemaProperty.type, STRING_STR));
                p.set(instantiate);
            }

            @Override
            public void visitInteger(IntegerField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, "int32")
                        .set(OpenApiSchemaProperty.type, "integer");
                p.set(instantiate);
            }

            @Override
            public void visitDate(DateField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, "date")
                        .set(OpenApiSchemaProperty.type, STRING_STR);
                p.set(instantiate);
            }

            @Override
            public void visitDateTime(DateTimeField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, "date-time")
                        .set(OpenApiSchemaProperty.type, STRING_STR);
                p.set(instantiate);
            }

            @Override
            public void visitString(StringField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, STRING_STR);
                p.set(instantiate);
            }

            @Override
            public void visitLong(LongField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, "int64")
                        .set(OpenApiSchemaProperty.type, "integer");
                p.set(instantiate);
            }

            @Override
            public void visitLongArray(LongArrayField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                OpenApiSchemaProperty.TYPE.instantiate()
                                        .set(OpenApiSchemaProperty.format, "int64")
                                        .set(OpenApiSchemaProperty.type, "integer"));
                p.set(instantiate);
            }

            @Override
            public void visitIntegerArray(IntegerArrayField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                OpenApiSchemaProperty.TYPE.instantiate()
                                        .set(OpenApiSchemaProperty.format, "int32")
                                        .set(OpenApiSchemaProperty.type, "integer"));
                p.set(instantiate);
            }

            @Override
            public void visitBoolean(BooleanField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, "boolean");
                p.set(instantiate);
            }

            @Override
            public void visitBooleanArray(BooleanArrayField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                OpenApiSchemaProperty.TYPE.instantiate()
                                        .set(OpenApiSchemaProperty.type, "boolean"));
                p.set(instantiate);
            }

            @Override
            public void visitStringArray(StringArrayField field) throws Exception {
                MutableGlob instantiate = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                OpenApiSchemaProperty.TYPE.instantiate()
                                        .set(OpenApiSchemaProperty.type, STRING_STR));
                p.set(instantiate);
            }

            @Override
            public void visitGlob(GlobField field) throws Exception {
                MutableGlob ref = buildSchema(field.getTargetType(), schemas);
                ref.set(OpenApiSchemaProperty.name, field.getName());
//                        .set(OpenApiSchemaProperty.format, "binary")
//                        .set(OpenApiSchemaProperty.type, "object");
                p.set(ref);
            }

            @Override
            public void visitUnionGlob(GlobUnionField field) throws Exception {
                MutableGlob ref = buildSchema(field.getTargetTypes().iterator().next(), schemas);
                ref.set(OpenApiSchemaProperty.name, field.getName());
//                        .set(OpenApiSchemaProperty.format, "binary")
//                        .set(OpenApiSchemaProperty.type, "object");
                p.set(ref);
            }

            @Override
            public void visitUnionGlobArray(GlobArrayUnionField field) throws Exception {
                MutableGlob ref = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                buildSchema(field.getTargetTypes().iterator().next(), schemas));
                p.set(ref);

            }

            @Override
            public void visitGlobArray(GlobArrayField field) throws Exception {
                MutableGlob ref = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.type, ARRAY_STR)
                        .set(OpenApiSchemaProperty.items,
                                buildSchema(field.getTargetType(), schemas));
                p.set(ref);
            }

            @Override
            public void visitBlob(BlobField field) throws Exception {
                MutableGlob ref = OpenApiSchemaProperty.TYPE.instantiate()
                        .set(OpenApiSchemaProperty.name, field.getName())
                        .set(OpenApiSchemaProperty.format, "binary")
                        .set(OpenApiSchemaProperty.type, "object");
                p.set(ref);
            }
        });
        return p.get();
    }

    public HttpServer init(ServerBootstrap serverBootstrap) {
        HttpRequestHttpAsyncRequestHandlerTree handler = new HttpRequestHttpAsyncRequestHandlerTree();
        serverBootstrap.registerHandler("*", handler);
        for (Map.Entry<String, Verb> stringVerbEntry : verbMap.entrySet()) {
            Verb verb = stringVerbEntry.getValue();
            GlobHttpRequestHandler globHttpRequestHandler = new GlobHttpRequestHandler(verb.complete(), verb.gzipCompress);
            var path = globHttpRequestHandler.createRegExp();
            handler.register(path, globHttpRequestHandler);
            for (HttpOperation operation : stringVerbEntry.getValue().operations) {

                MutableGlob logs = HttpAPIDesc.TYPE.instantiate()
                        .set(HttpAPIDesc.serverName, serverInfo)
                        .set(HttpAPIDesc.url, stringVerbEntry.getKey())
                        .set(HttpAPIDesc.queryParam, GSonUtils.encodeGlobType(operation.getQueryParamType()))
                        .set(HttpAPIDesc.body, GSonUtils.encodeGlobType(operation.getBodyType()))
                        .set(HttpAPIDesc.returnType, GSonUtils.encodeGlobType(operation.getReturnType()))
                        .set(HttpAPIDesc.comment, operation.getComment());
                LOGGER.info("Api : {}", GSonUtils.encode(logs, false));
            }
        }
        if (Strings.isNotEmpty(serverInfo)) {
            serverBootstrap.setServerInfo(serverInfo);
        }
        return serverBootstrap.create();
    }

    public Pair<HttpServer, Integer> startAndWaitForStartup(ServerBootstrap bootstrap) {
        HttpServer server = init(bootstrap);
        try {
            server.start();
            server.getEndpoint().waitFor();
            InetSocketAddress address = (InetSocketAddress) server.getEndpoint().getAddress();
            int port = address.getPort();
            openApiDoc = createOpenApiDoc(port);
            LOGGER.info("OpenApi doc : {}", GSonUtils.encode(openApiDoc, false));
            return Pair.makePair(server, port);
        } catch (Exception e) {
            String message = "Fail to start server" + serverInfo;
            LOGGER.error(message);
            throw new RuntimeException(message, e);
        }
    }

    public interface OperationInfo {
        OperationInfo declareReturnType(GlobType globType);

        OperationInfo comment(String comment);

        void addHeader(String name, String value);
    }

    public static class HttpAPIDesc {
        public static GlobType TYPE;

        public static StringField serverName;

        public static StringField url;

        public static StringField verb;

        @IsJsonContentAnnotation
        public static StringField queryParam;

        @IsJsonContentAnnotation
        public static StringField body;

        @IsJsonContentAnnotation
        public static StringField returnType;

        public static StringField comment;

        static {
            GlobTypeLoaderFactory.create(HttpAPIDesc.class).load();
        }

    }

    private static class HttpRequestHttpAsyncRequestHandlerTree implements HttpAsyncRequestHandler<HttpRequest> {
        StrNode[] nodes = new StrNode[0];
        public HttpAsyncRequestConsumer<HttpRequest> processRequest(HttpRequest httpRequest, HttpContext httpContext)  {
            return new BasicAsyncRequestConsumer();
        }

        public void handle(HttpRequest httpRequest, HttpAsyncExchange httpExchange, HttpContext context) throws HttpException, IOException {
            var requestLine = httpRequest.getRequestLine();
            String uri = requestLine.getUri();
            int i = uri.indexOf("?");
            var urlStr = uri.substring(1, i == -1 ? uri.length() : i); // remove first /
            String paramStr = i == -1 ? null : uri.substring(i + 1);
            String[] split = urlStr.split("/");
            nodes[split.length].dispatch(split, paramStr, httpRequest, httpExchange, context);
        }

        public void register(Collection<String> path, GlobHttpRequestHandler globHttpRequestHandler) {
            int length = nodes.length;
            if (length <= path.size()) {
                nodes = Arrays.copyOf(nodes, path.size() + 1);
                for (; length < nodes.length; length++) {
                    nodes[length] = new StrNode();
                }
            }
            nodes[path.size()].register(path, globHttpRequestHandler);
        }
    }

    static class StrNode {
        private SubStrNode[] subStrNodes = new SubStrNode[0];
        public void dispatch(String[] path, String paramStr, HttpRequest httpRequest, HttpAsyncExchange httpExchange, HttpContext context) throws IOException {
            for (SubStrNode subStrNode : this.subStrNodes) {
                if (subStrNode.match(path)) {
                    subStrNode.globHttpRequestHandler.handle(path, paramStr, httpRequest, httpExchange, context);
                    return;
                }
            }
        }

        public void register(Collection<String> path, GlobHttpRequestHandler globHttpRequestHandler) {
            subStrNodes = Arrays.copyOf(subStrNodes, subStrNodes.length + 1);
            subStrNodes[subStrNodes.length - 1] = new SubStrNode(path, globHttpRequestHandler);
        }
    }

    static class SubStrNode {
        private String[] path;
        private GlobHttpRequestHandler globHttpRequestHandler;

        public SubStrNode(Collection<String> path, GlobHttpRequestHandler globHttpRequestHandler) {
            this.path = path.toArray(String[]::new);
            this.globHttpRequestHandler = globHttpRequestHandler;
        }

        boolean match(String[] path) {
            String[] strings = this.path;
            for (int i = 0, stringsLength = strings.length; i < stringsLength; i++) {
                String s = strings[i];
                if (s != null) {
                    if (!s.equals(path[i])) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    private class OpenApiFieldVisitor extends FieldVisitor.AbstractWithErrorVisitor {
        private Glob schema;
        private Map<GlobType, Glob> schemas;

        public OpenApiFieldVisitor(Map<GlobType, Glob> schemas) {
            this.schemas = schemas;
        }

        @Override
        public void visitInteger(IntegerField field) throws Exception {
            createSchema("integer", "int32");
        }

        private void createSchema(String type, String format) {
            schema = create(type, format);
        }

        private MutableGlob create(String type, String format) {
            MutableGlob set = OpenApiSchemaProperty.TYPE.instantiate()
                    .set(OpenApiSchemaProperty.type, type);
            if (format != null) {
                set.set(OpenApiSchemaProperty.format, format);
            }
            return set;
        }

        @Override
        public void visitDouble(DoubleField field) throws Exception {
            createSchema(NUMBER_STR, DOUBLE_STR);
        }

        @Override
        public void visitString(StringField field) throws Exception {
            createSchema(STRING_STR, null);
        }

        @Override
        public void visitBoolean(BooleanField field) throws Exception {
            createSchema("boolean", null);
        }

        @Override
        public void visitLong(LongField field) throws Exception {
            createSchema("integer", "int64");
        }

        @Override
        public void visitStringArray(StringArrayField field) throws Exception {
            createArray(STRING_STR, null);
        }

        @Override
        public void visitDoubleArray(DoubleArrayField field) throws Exception {
            createArray(NUMBER_STR, DOUBLE_STR);
        }

        @Override
        public void visitIntegerArray(IntegerArrayField field) throws Exception {
            createArray("integer", "int32");
        }

        @Override
        public void visitLongArray(LongArrayField field) throws Exception {
            createArray("integer", "int64");
        }

        @Override
        public void visitDate(DateField field) throws Exception {
            createSchema(STRING_STR, "date");
        }

        @Override
        public void visitDateTime(DateTimeField field) throws Exception {
            createSchema(STRING_STR, "date-time");
        }

        @Override
        public void visitBooleanArray(BooleanArrayField field) throws Exception {
            createArray("boolean", null);
        }

        @Override
        public void visitBigDecimal(BigDecimalField field) throws Exception {
            createSchema(STRING_STR, BIG_DECIMAL_STR);
        }

        @Override
        public void visitBigDecimalArray(BigDecimalArrayField field) throws Exception {
            createArray(STRING_STR, BIG_DECIMAL_STR);
        }

        private void createArray(String type, String format) {
            schema = OpenApiSchemaProperty.TYPE.instantiate()
                    .set(OpenApiSchemaProperty.type, ARRAY_STR)
                    .set(OpenApiSchemaProperty.items, create(type, format));
        }

        @Override
        public void visitGlob(GlobField field) throws Exception {
            schema = buildSchema(field.getGlobType(), schemas);
        }

        @Override
        public void visitGlobArray(GlobArrayField field) throws Exception {
            schema = OpenApiSchemaProperty.TYPE.instantiate()
                    .set(OpenApiSchemaProperty.type, ARRAY_STR)
                    .set(OpenApiSchemaProperty.items, buildSchema(field.getTargetType(), schemas));

        }
    }

    public class Verb {
        private final String url;
        private final GlobType queryUrl;
        private boolean gzipCompress = false;
        private List<HttpOperation> operations = new ArrayList<>();
        private final Map<String, String> headers = new HashMap<>();


        public Verb(String url, GlobType queryUrl) {
            this.url = url;
            this.queryUrl = queryUrl;
        }

        public Verb setGzipCompress() {
            this.gzipCompress = true;
            return this;
        }

        public Verb setGzipCompress(boolean gzipCompress) {
            this.gzipCompress = true;
            return this;
        }

        public OperationInfo get(GlobType paramType, HttpTreatment httpTreatment) {
            DefaultHttpOperation operation = new DefaultHttpOperation(HttpOp.get, null, paramType, httpTreatment);
            operations.add(operation);
            return new DefaultOperationInfo(operation);
        }

        public OperationInfo post(GlobType bodyParam, GlobType paramType, HttpTreatment httpTreatment) {
            DefaultHttpOperation operation = new DefaultHttpOperation(HttpOp.post, bodyParam, paramType, httpTreatment);
            operations.add(operation);
            return new DefaultOperationInfo(operation);
        }

        public OperationInfo put(GlobType bodyParam, GlobType paramType, HttpTreatment httpTreatment) {
            DefaultHttpOperation operation = new DefaultHttpOperation(HttpOp.put, bodyParam, paramType, httpTreatment);
            operations.add(operation);
            return new DefaultOperationInfo(operation);
        }

        public OperationInfo delete(GlobType paramType, HttpTreatment httpTreatment) {
            DefaultHttpOperation operation = new DefaultHttpOperation(HttpOp.delete, null, paramType, httpTreatment);
            operations.add(operation);
            return new DefaultOperationInfo(operation);
        }

        public void addHeader(String name, String value) {
            headers.put(name, value);
        }

        HttpReceiver complete() {
            DefaultHttpReceiver defaultHttpReceiver = new DefaultHttpReceiver(url, queryUrl, operations.toArray(new HttpOperation[0]));
            headers.forEach(defaultHttpReceiver::addHeader);
            return defaultHttpReceiver;
        }

        private class DefaultOperationInfo implements OperationInfo {
            private final DefaultHttpOperation operation;

            public DefaultOperationInfo(DefaultHttpOperation operation) {
                this.operation = operation;
            }

            public OperationInfo declareReturnType(GlobType type) {
                operation.withReturnType(type);
                return this;
            }

            public OperationInfo comment(String comment) {
                operation.withComment(comment);
                return this;
            }

            public void addHeader(String name, String value) {
                operation.addHeader(name, value);
            }
        }
    }
}
