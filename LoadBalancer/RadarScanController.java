package pt.ulisboa.tecnico.cnv.controller;

import java.io.IOException;
import java.io.OutputStream;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException
import java.net.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Instance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class RadarScanController {

  private static ControllerArgumentParser controllerArgumentParser = null;
  private static Map<Long, String> pendingRequests;
  private static AmazonEC2 ec2;
  private static Map<Long, Instance> instances;

  // Init AWS services here
  private static void init() throws Exception {
    AWSCredentials credentials = null;

    try {
      credentials = new ProfileCredentialsProvider().getCredentials();
    } catch (Exception e) {
      throw new AmazonClientException(
        "Cannot load the credentials from the credential profiles file. " +
        "Please make sure that your credentials file is at the correct " +
        "location (~/.aws/credentials), and is in valid format.", e
      );
    }

    ec2 = AmazonEC2ClientBuilder.standard()
      .withRegion("us-east-1")
      .withCredentials(new AWSStaticCredentialsProvider(credentials))
      .build();

    // init dynamo

    // init cloudwatch
  }

  public static void main(String[] args) throws Exception {

    try {
      controllerArgumentParser = new ControllerArgumentParser(args);
    }
    catch (Exception e) {
      System.out.println(e);
      return;
    }

    System.out.println("> Parsed server arguments...");

    init();

    pendingRequests = new ConcurrentHashMap<>();
    instances = new ConcurrentHashSet<>();

    HttpServer server = HttpServer.create(new InetSocketAddress(
        serverArgumentParser.getServerAddress(),
        serverArgumentParser.getServerPort()), 0);

    server.createContext("/scan", new ScanHandler());
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    // Start AutoScaler thread

    // Start HealthCheck thread
    Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(new Runnable() {
      @Override
      public void run() {
        for (Instance instance : instances.values()) {
          String name = instance.getInstanceId();
          String state = instance.getState().getName();
          String ip = instance.getPrivateIp();

          if (state.equals("running")) {

          }
        }
      }
    }, 120, 30, TimeUnit.SECONDS);
  }

  static class ScanHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange t) throws IOException {
      final int threadId = Thread.currentThread().getId();
      final String query = t.getRequestURI().getQuery();
      final String[] queryPairs = query.split("&");
      final int x0, x1, y0, y1;

      String strategy = null;
      Integer scanAreaSize = null;

      pendingRequests.put(threadId, query);

      for (String queryPair : queryPairs) {
        String[] queryParameter = queryPair.split("=");

        if (queryParameter[0].equals("s")) {
          strategy = queryParameter[1];
        } else if (queryParameter[0].equals("x0")) {
          x0 = Integer.parseInt(queryParameter[1]);
        } else if (queryParameter[0].equals("x1")) {
          x1 = Integer.parseInt(queryParameter[1]);
        } else if (queryParameter[0].equals("y0")) {
          y0 = Integer.parseInt(queryParameter[1]);
        } else if (queryParameter[0].equals("y1")) {
          y1 = Integer.parseInt(queryParameter[1]);
        }
      }

      // Access dynamoDB

      // Determine instance to send request to

      Instance instance;

      // TODO: Change to java 11 HttpClient
      try {
        URL url = new URL("http://" + instance.getPrivateIp() + ":8000/scan?" + query);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET"); // default can remove
        connection.setConnectTimeOut(5000);
        connection.setReadTimeOut(5000);

        int status = connection.getResponseCode();

        if (status == HttpURLConnection.HTTP_OK) {
          final InputStream in = connection.getInputStream();
          final OutputStream os = t.getResponseBody();

          int size = 0;
          int readBytes;
          byte[] buffer = new byte[4096];

          while ((readBytes = in.read(buffer)) != -1) {
            os.write(buffer, 0, readBytes);
            size += readBytes;
          }

          t.sendResponseHeaders(200, size);

          os.close();
        }

      } catch (MalformedURLException mue) {
        e.printStackTrace();
      } catch (IOException ioe) {
        e.printStackTrace();
      } finally {
        url.disconnect();
      }
    }
  }
}
