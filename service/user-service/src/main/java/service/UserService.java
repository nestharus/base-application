package service;

public class UserService {
  /*
  public class GeldataWorkerVerticle extends AbstractVerticle {

    // Define an Event Bus address for this service
    public static final String GELDATA_QUERY_ADDRESS = "geldata.query.service";

    private GelClientPool gelClientPool;

    @Override
    public void start(Promise<Void> startPromise) {
        System.out.println("GeldataWorkerVerticle starting on thread: " + Thread.currentThread().getName());

        // Initialize GelClientPool. In a real app, you might inject this or configure it.
        gelClientPool = new GelClientPool();

        // Register an Event Bus consumer for the geldata query address
        vertx.eventBus().consumer(GELDATA_QUERY_ADDRESS, this::handleGeldataQuery);

        startPromise.complete();
    }

    private void handleGeldataQuery(Message<String> message) {
        String query = message.body(); // The message body is the query from the router verticle
        System.out.println("GeldataWorkerVerticle received query: '" + query + "' on thread: " + Thread.currentThread().getName());

        // Use executeBlocking to run the Geldata query, as it's a potentially blocking operation.
        // This will run on a Vert.x worker thread, not this verticle's thread.
        vertx.executeBlocking(
            () -> {
                // This code runs on a worker thread
                System.out.println("Executing Geldata query (blocking) on worker thread: " + Thread.currentThread().getName());
                // Simulate blocking I/O with Geldata
                // The .join() here blocks the worker thread until the result is ready
                return gelClientPool.querySingle(String.class, query).join();
            },
            false // Not an ordered operation
        )
        .onSuccess(result -> {
            // This code runs back on the GeldataWorkerVerticle's event loop thread
            System.out.println("Geldata query completed, replying with: '" + result + "' from thread: " + Thread.currentThread().getName());
            message.reply(result); // Send the result back to the sender (HttpRouterVerticle)
        })
        .onFailure(error -> {
            // This code runs back on the GeldataWorkerVerticle's event loop thread
            System.err.println("Geldata query failed: " + error.getMessage() + " from thread: " + Thread.currentThread().getName());
            // Reply with a failure status and message
            message.fail(500, "Geldata query error: " + error.getMessage());
        });
    }
   */

  /*
  @Override
    public void start(Promise<Void> startPromise) {
        System.out.println("HttpRouterVerticle starting on thread: " + Thread.currentThread().getName());

        Router router = Router.router(vertx);

        // Define a route for handling Geldata queries
        router.get("/query/geldata").handler(routingContext -> {
            HttpServerResponse response = routingContext.response();
            String userQuery = routingContext.request().getParam("sql"); // Get SQL from query param

            if (userQuery == null || userQuery.isEmpty()) {
                userQuery = "SELECT 'Default Data from Geldata!'"; // Default query
            }

            System.out.println("HttpRouterVerticle received HTTP request for query: '" + userQuery + "' on thread: " + Thread.currentThread().getName());

            response.putHeader("content-type", "text/plain");

            // Send a request to the GeldataWorkerVerticle via the Event Bus
            // eventBus().request() returns a Future, which will be completed with the worker's reply
            vertx.eventBus().request(GeldataWorkerVerticle.GELDATA_QUERY_ADDRESS, userQuery)
                .onSuccess(reply -> {
                    // This callback runs back on the HttpRouterVerticle's event loop thread
                    System.out.println("HttpRouterVerticle received reply from worker: '" + reply.body() + "' on thread: " + Thread.currentThread().getName());
                    response.end("Geldata Query Result:\n" + reply.body());
                })
                .onFailure(error -> {
                    // This callback runs back on the HttpRouterVerticle's event loop thread
                    System.err.println("HttpRouterVerticle received error from worker: " + error.getMessage() + " on thread: " + Thread.currentThread().getName());
                    response.setStatusCode(500).end("Error querying Geldata: " + error.getMessage());
                });
        });

        // Other routes can go here
        router.get("/").handler(routingContext -> {
            routingContext.response().end("Hello from HTTP Router! Try /query/geldata?sql=YOUR_QUERY_HERE");
        });

        // Start the HTTP server
        vertx.createHttpServer()
            .requestHandler(router)
            .listen(8080, http -> {
                if (http.succeeded()) {
                    startPromise.complete();
                    System.out.println("HTTP server started on port 8080");
                } else {
                    startPromise.fail(http.cause());
                    System.err.println("HTTP server failed to start: " + http.cause().getMessage());
                }
            });
    }
   */
}
