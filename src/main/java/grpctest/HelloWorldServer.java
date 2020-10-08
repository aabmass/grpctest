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
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import io.opencensus.common.Scope;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceConfiguration;
import io.opencensus.exporter.trace.stackdriver.StackdriverTraceExporter;
import io.opencensus.trace.Tracer;
import io.opencensus.trace.Tracing;

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
    Aggregation latencyDistribution = Distribution
        .create(BucketBoundaries.create(Arrays.asList(0.0, 25.0, 100.0, 200.0, 400.0, 800.0, 10000.0)));

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
        traceConfig.getActiveTraceParams().toBuilder().setSampler(Samplers.alwaysSample()).build());
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
      Scope ss = tracer.spanBuilder("grpctest_span").startScopedSpan();
      MeasureMap mmap = STATS_RECORDER.newMeasureMap();
      mmap.put(M_LATENCY_MS, Math.random() * 100);
      ExemplarUtils.putSpanContextAttachments(mmap, tracer.getCurrentSpan().getContext());
      mmap.record();

      try {
        HelloResponse res = HelloResponse.newBuilder().setResponse("Hello " + req.getMessage() + ", to you!").build();
        responseObserver.onNext(res);
      } finally {
        ss.close();
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
