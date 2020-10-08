package grpctest;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.stub.StreamObserver;
import io.opencensus.common.Duration;
import io.opencensus.contrib.exemplar.util.ExemplarUtils;
import io.opencensus.contrib.grpc.metrics.RpcViews;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsConfiguration;
import io.opencensus.exporter.stats.stackdriver.StackdriverStatsExporter;
import io.opencensus.stats.Aggregation;
import io.opencensus.stats.BucketBoundaries;
import io.opencensus.stats.MeasureMap;
import io.opencensus.stats.Stats;
import io.opencensus.stats.StatsRecorder;
import io.opencensus.stats.View;
import io.opencensus.stats.ViewManager;
import io.opencensus.stats.Aggregation.Distribution;
import io.opencensus.stats.Measure.MeasureDouble;
import io.opencensus.stats.View.Name;
import io.opencensus.trace.config.TraceConfig;
import io.opencensus.trace.samplers.Samplers;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.trace.Span;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.Span.Options;

public class HelloWorldServer {

  private static final Logger logger = Logger.getLogger(HelloWorldServer.class.getName());
  private static final Tracer tracer = Tracing.getTracer();
  private static final MeasureDouble M_LATENCY_MS = MeasureDouble.create("latency", "The latency in milliseconds",
      "ms");

  private static final StatsRecorder STATS_RECORDER = Stats.getStatsRecorder();

  private Server server;

  private void start() throws IOException {
    /* The port on which the server should run */
    int port = 50051;
    server = ServerBuilder.forPort(port).addService(new HelloServiceImpl())
        .addService(ProtoReflectionService.newInstance()).build().start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown
        // hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        try {
          HelloWorldServer.this.stop();
        } catch (InterruptedException e) {
          e.printStackTrace(System.err);
        }
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() throws InterruptedException {
    if (server != null) {
      server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Await termination on the main thread since the grpc library uses daemon
   * threads.
   */
  private void blockUntilShutdown() throws InterruptedException {
    if (server != null) {
      server.awaitTermination();
    }
  }

  private static void enableViews() {
    Aggregation latencyDistribution = Distribution.create(
        BucketBoundaries.create(Arrays.asList(0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0)));

    View view = View.create(Name.create("myapp/latency"), "The distribution of the latencies", M_LATENCY_MS,
        latencyDistribution, Collections.emptyList());

    // Ensure that they are registered so
    // that measurements won't be dropped.
    ViewManager manager = Stats.getViewManager();
    manager.registerView(view);

    // Enable GRPC views
    RpcViews.registerAllGrpcViews();
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    final HelloWorldServer server = new HelloWorldServer();
    String gcpProjectId = envOrAlternative("GCP_PROJECT_ID");

    // For demo purposes, always sample
    TraceConfig traceConfig = Tracing.getTraceConfig();
    traceConfig.updateActiveTraceParams(
        traceConfig.getActiveTraceParams().toBuilder().setSampler(Samplers.probabilitySampler(1 / 10.0)).build());
    enableViews();

    // Enable OpenCensus exporters to export metrics to Stackdriver Monitoring.
    // Exporters use Application Default Credentials to authenticate.
    // See
    // https://developers.google.com/identity/protocols/application-default-credentials
    // for more details.
    // The minimum reporting period for Stackdriver is 1 minute.
    // Create the Stackdriver stats exporter
    StackdriverStatsExporter.createAndRegister(StackdriverStatsConfiguration.builder().setProjectId(gcpProjectId)
        .setExportInterval(Duration.create(5, 0)).build());

    StackdriverTraceExporter
        .createAndRegister(StackdriverTraceConfiguration.builder().setProjectId(gcpProjectId).build());
    server.start();
    server.blockUntilShutdown();
  }

  static class HelloServiceImpl extends HelloServiceGrpc.HelloServiceImplBase {

    @Override
    public void sayHello(HelloRequest req, StreamObserver<HelloResponse> responseObserver) {
      Span span = tracer.spanBuilder("grpctest_span").startSpan();
      MeasureMap mmap = STATS_RECORDER.newMeasureMap();
      double delay = (new Random().nextGaussian() + 5) * 10;
      long startTime = System.nanoTime();

      try {
        HelloResponse res = HelloResponse.newBuilder().setResponse("Hello " + req.getMessage() + ", to you!").build();
        // Simulated normal latency
        Thread.sleep((long) delay);
        responseObserver.onNext(res);
      } catch (InterruptedException i) {
      } finally {
        span.end();

        double latency = (System.nanoTime() - startTime) / 1e6;
        logger.info("Measured a latency of " + latency + "ms");
        mmap.put(M_LATENCY_MS, latency);

        if (span.getOptions().contains(Options.RECORD_EVENTS)) {
          ExemplarUtils.putSpanContextAttachments(mmap, span.getContext());
        }

        mmap.record();
        responseObserver.onCompleted();
      }
    }
  }

  private static String envOrAlternative(String key, String... alternatives) {
    String value = System.getenv().get(key);
    if (value != null && value != "")
      return value;

    // Otherwise now look for the alternatives.
    for (String alternative : alternatives) {
      if (alternative != null && alternative != "") {
        value = alternative;
        break;
      }
    }

    return value;
  }
}
