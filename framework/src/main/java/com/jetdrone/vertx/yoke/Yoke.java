// Copyright 2011-2012 the original author or authors.
//
// @package com.jetdrone.vertx.yoke
package com.jetdrone.vertx.yoke;

import com.jetdrone.vertx.yoke.core.Context;
import com.jetdrone.vertx.yoke.core.RequestWrapper;
import com.jetdrone.vertx.yoke.middleware.YokeRequest;
import com.jetdrone.vertx.yoke.middleware.YokeResponse;
import com.jetdrone.vertx.yoke.store.SessionStore;
import com.jetdrone.vertx.yoke.store.SharedDataSessionStore;
import com.jetdrone.vertx.yoke.util.YokeException;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.Handler;
import org.vertx.java.core.Vertx;
import org.vertx.java.core.http.HttpServer;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.http.HttpServerResponse;
import org.vertx.java.core.logging.Logger;
import org.vertx.java.platform.Verticle;

import java.util.*;

// # Yoke
//
// Yoke is a chain executor of middleware for Vert.x 2.x. The goal of this library is not to provide a web application
// framework but the backbone that helps the creation of web applications.
//
// Yoke works in a similar way to Connect middleware. Users start by declaring which middleware components want to use
// and then start an http server either managed by Yoke or provided by the user (say when you need https).
//
// Yoke has no extra dependencies than Vert.x itself so it is self contained.
public class Yoke implements RequestWrapper {

    // Vert.x instance
    //
    // @private
    // @property vertx
    private final Vertx vertx;

    // internal logger
    //
    // @private
    // @property logger
    private final Logger logger;

    // request wrapper in use
    //
    // @private
    // @property requestWrapper
    private final RequestWrapper requestWrapper;

    // default context used by all requests
    //
    // @private
    // @property defaultContext
    //
    // @example
    //      {
    //        title: "Yoke",
    //        x-powered-by: true,
    //        trust-proxy: true
    //      }
    protected final Map<String, Object> defaultContext = new HashMap<>();

    // The internal registry of [render engines](Engine.html)
    //
    // @private
    // @property engineMap
    private final Map<String, Engine> engineMap = new HashMap<>();

    // Creates a Yoke instance.
    //
    // This constructor should be called from a verticle and pass a valid Vertx instance. This instance will be shared
    // with all registered middleware. The reason behind this is to allow middleware to use Vertx features such as file
    // system and timers.
    //
    // @constructor
    // @param {Verticle} verticle
    //
    // @example
    //      public class MyVerticle extends Verticle {
    //          public void start() {
    //              final Yoke yoke = new Yoke(this);
    //              ...
    //          }
    //      }
    public Yoke(Verticle verticle) {
        this(verticle.getVertx(), verticle.getContainer().logger(), null);
    }

    // Creates a Yoke instance.
    //
    // This constructor should be called from a verticle and pass a valid Vertx instance and a Logger. This instance
    // will be shared with all registered middleware. The reason behind this is to allow middleware to use Vertx
    // features such as file system and timers.
    //
    // @constructor
    // @param {Verticle} verticle
    // @param {Logger} logger
    //
    // @example
    //      public class MyVerticle extends Verticle {
    //          public void start() {
    //              final Yoke yoke = new Yoke(getVertx(),
    //                  getContainer().logger());
    //              ...
    //          }
    //      }
    public Yoke(Vertx vertx, Logger logger) {
        this(vertx, logger, null);
    }

    // Creates a Yoke instance.
    //
    // This constructor should be called internally or from other language bindings.
    //
    // @constructor
    // @internal
    // @param {Verticle} verticle
    // @param {Logger} logger
    // @param {RequestWrapper} requestWrapper
    //
    // @example
    //      public class MyVerticle extends Verticle {
    //          public void start() {
    //              final Yoke yoke = new Yoke(getVertx(),
    //                  getContainer().logger(),
    //                  new RequestWrapper() {...});
    //              ...
    //          }
    //      }
    public Yoke(Vertx vertx, Logger logger, RequestWrapper requestWrapper) {
        this.vertx = vertx;
        this.logger = logger;
        this.requestWrapper = requestWrapper == null ? this : requestWrapper;
        defaultContext.put("title", "Yoke");
        defaultContext.put("x-powered-by", true);
        defaultContext.put("trust-proxy", true);
        store = new SharedDataSessionStore(vertx, "yoke.sessiondata");
    }

    // Mounted middleware represents a binding of a Middleware instance to a specific url path.
    // @private
    private static class MountedMiddleware {
        final String mount;
        final Middleware middleware;

        // Constructs a new Mounted Middleware
        // @constructor
        // @private
        //
        // @param {String} mount Mount path
        // @param {Middleware} middleware Middleware to use on the path.
        private MountedMiddleware(String mount, Middleware middleware) {
            this.mount = mount;
            this.middleware = middleware;
        }
    }

    // Ordered list of mounted middleware in the chain
    //
    // @private
    // @property middlewareList
    private final List<MountedMiddleware> middlewareList = new ArrayList<>();

    // Special middleware used for error handling
    //
    // @private
    // @property errorHandler
    private Middleware errorHandler;

    // Adds a Middleware to the chain. If the middleware is an Error Handler Middleware then it is
    // treated differently and only the last error handler is kept.
    //
    // You might want to add a middleware that is only supposed to run on a specific route (path prefix).
    // In this case if the request path does not match the prefix the middleware is skipped automatically.
    //
    // @method use
    // @param {String} route The route prefix for the middleware
    // @param {Middleware[]} middleware The middleware add to the chain
    //
    // @example
    //     yoke.use("/login", new CustomLoginMiddleware());
    public Yoke use(String route, Middleware... middleware) {
        for (Middleware m : middleware) {
            // when the type of middleware is error handler then the route is ignored and
            // the middleware is extracted from the execution chain into a special placeholder
            // for error handling
            if (m.isErrorHandler()) {
                errorHandler = m;
            } else {
                middlewareList.add(new MountedMiddleware(route, m));
            }

            // initialize the middleware with the current Vert.x and Logger
            m.init(vertx, logger, route);
        }
        return this;
    }

    // Adds a middleware to the chain with the prefix "/".
    // @method use
    // @param {Middleware} middleware The middleware add to the chain
    //
    public Yoke use(Middleware... middleware) {
        return use("/", middleware);
    }

    // Adds a Handler to a route. The behaviour is similar to the middleware, however this
    // will be a terminal point in the execution chain. In this case any middleware added
    // after will not be executed. However you should care about the route which may lead
    // to skip this middleware.
    //
    // The idea to user a Handler is to keep the API familiar with the rest of the Vert.x
    // API.
    //
    // @method use
    // @param {String} route The route prefix for the middleware
    // @param {Handler} handler The Handler to add
    //
    // @example
    //     yoke.use("/login", new Handler<YokeRequest>() {
    //         public void handle(YokeRequest request) {
    //             request.response.end("Hello");
    //         }
    //     });
    public Yoke use(String route, final Handler<YokeRequest> handler) {
        middlewareList.add(new MountedMiddleware(route, new Middleware() {
            @Override
            public void handle(YokeRequest request, Handler<Object> next) {
                handler.handle(request);
            }
        }));
        return this;
    }

    //
    // Adds a Handler to a route.
    //
    // @method use
    // @param {Handler} handler The Handler to add
    //
    // @example
    //     yoke.use("/login", new Handler<YokeRequest>() {
    //         public void handle(YokeRequest request) {
    //             request.response.end("Hello");
    //         }
    //     });
    public Yoke use(Handler<YokeRequest> handler) {
        return use("/", handler);
    }

    // Adds a Render Engine to the library. Render Engines are Template engines you
    // might want to use to speed the development of your application. Once they are
    // registered you can use the method render in the YokeResponse to
    // render a template.
    //
    // @method engine
    // @param {String} extension The file extension for this template engine e.g.: .jsp
    // @param {Engine} engine The implementation of the engine
    public Yoke engine(String extension, Engine engine) {
        engine.setVertx(vertx);
        engineMap.put(extension, engine);
        return this;
    }

    // Special store engine used for accessing session data
    //
    // @private
    // @property store
    private SessionStore store;

    public Yoke store(SessionStore store) {
        this.store = store;
        return this;
    }

    // When you need to share global properties with your requests you can add them
    // to Yoke and on every request they will be available as request.get(String)
    //
    // @method set
    // @param {String} key unique identifier
    // @param {Object} value Any non null value, nulls are not saved
    //
    public Yoke set(String key, Object value) {
        if (value == null) {
            defaultContext.remove(key);
        } else {
            defaultContext.put(key, value);
        }

        return this;
    }

    // Starts the server listening at a given port bind to all available interfaces.
    //
    // @method listen
    // @param {int} port the server TCP port
    // @return {Yoke}
    public Yoke listen(int port) {
        return listen(port, "0.0.0.0", null);
    }

    // Starts the server listening at a given port bind to all available interfaces.
    //
    // @method listen
    // @param {int} port the server TCP port
    // @param {Handler} handler for asynchronous result of the listen operation
    // @return {Yoke}
    public Yoke listen(int port, Handler<Boolean> handler) {
        return listen(port, "0.0.0.0", handler);
    }

    // Starts the server listening at a given port and given address.
    //
    // @method listen
    // @param {int} port the server TCP port
    // @return {Yoke}
    public Yoke listen(int port, String address) {
        return listen(port, address, null);
    }

    // Starts the server listening at a given port and given address.
    //
    // @method listen
    // @param {int} port the server TCP port
    // @param {Handler} handler for asynchronous result of the listen operation
    // @return {Yoke}
    public Yoke listen(int port, String address, final Handler<Boolean> handler) {
        HttpServer server = vertx.createHttpServer();

        listen(server);

        if (handler != null) {
            server.listen(port, address, new Handler<AsyncResult<HttpServer>>() {
                @Override
                public void handle(AsyncResult<HttpServer> listen) {
                    handler.handle(listen.succeeded());
                }
            });
        } else {
            server.listen(port, address);
        }
        return this;
    }

    // Default implementation of the request wrapper
    //
    // @internal
    // @method wrap
    // @param {HttpServerRequest} request
    // @param {boolean} secure
    // @param {Map} context
    // @param {Map} engines
    @Override
    public YokeRequest wrap(HttpServerRequest request, boolean secure, Map<String, Engine> engines) {
        // the context map is shared with all middlewares
        final Map<String, Object> context = new Context(defaultContext);
        YokeResponse response = new YokeResponse(request.response(), context, engines);
        return new YokeRequest(request, response, secure, context, store);
    }

    // Starts listening at a already created server.
    //
    // @method listen
    // @param {HttpServer} server
    // @return {Yoke}
    public Yoke listen(final HttpServer server) {
        // is this server HTTPS?
        final boolean secure = server.isSSL();

        server.requestHandler(new Handler<HttpServerRequest>() {
            @Override
            public void handle(HttpServerRequest req) {
                final YokeRequest request = requestWrapper.wrap(req, secure, engineMap);

                // add x-powered-by header is enabled
                Boolean poweredBy = request.get("x-powered-by");
                if (poweredBy != null && poweredBy) {
                    request.response().putHeader("x-powered-by", "yoke");
                }

                new Handler<Object>() {
                    int currentMiddleware = -1;
                    @Override
                    public void handle(Object error) {
                        if (error == null) {
                            currentMiddleware++;
                            if (currentMiddleware < middlewareList.size()) {
                                MountedMiddleware mountedMiddleware = middlewareList.get(currentMiddleware);

                                if (request.path().startsWith(mountedMiddleware.mount)) {
                                    Middleware middlewareItem = mountedMiddleware.middleware;
                                    middlewareItem.handle(request, this);
                                } else {
                                    // the middleware was not mounted on this uri, skip to the next entry
                                    handle(null);
                                }
                            } else {
                                HttpServerResponse response = request.response();
                                // reached the end and no handler was able to answer the request
                                response.setStatusCode(404);
                                response.setStatusMessage(HttpResponseStatus.valueOf(404).reasonPhrase());
                                if (errorHandler != null) {
                                    errorHandler.handle(request, null);
                                } else {
                                    response.end(HttpResponseStatus.valueOf(404).reasonPhrase());
                                }
                            }
                        } else {
                            request.put("error", error);
                            if (errorHandler != null) {
                                errorHandler.handle(request, null);
                            } else {
                                HttpServerResponse response = request.response();

                                int errorCode;
                                // if the error was set on the response use it
                                if (response.getStatusCode() >= 400) {
                                    errorCode = response.getStatusCode();
                                } else {
                                    // if it was set as the error object use it
                                    if (error instanceof Number) {
                                        errorCode = ((Number) error).intValue();
                                    } else if (error instanceof YokeException) {
                                        errorCode = ((YokeException) error).getErrorCode().intValue();
                                    } else {
                                        // default error code
                                        errorCode = 500;
                                    }
                                }

                                response.setStatusCode(errorCode);
                                response.setStatusMessage(HttpResponseStatus.valueOf(errorCode).reasonPhrase());
                                response.end(HttpResponseStatus.valueOf(errorCode).reasonPhrase());
                            }
                        }
                    }
                }.handle(null);
            }
        });
        return this;
    }
}
