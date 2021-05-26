package pt.ulisboa.tecnico.cnv.controller;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class RadarScanController {

  private static ControllerArgumentParser controllerArgumentParser = null;
  private static Map<Long, String> pendingRequests;

  public static void main(String[] args) throws Exception {

    try {
      controllerArgumentParser = new ControllerArgumentParser(args);
    }
    catch (Exception e) {
      System.out.println(e);
      return;
    }

    System.out.println("> Parsed server arguments...");

    pendingRequests = new ConcurrentHashMap<>();

    HttpServer server = HttpServer.create(new InetSocketAddress(
        serverArgumentParser.getServerAddress(),
        serverArgumentParser.getServerPort()), 0);

    server.createContext("/scan", new ScanHandler());
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    // Start AutoScaler thread

    // Start HealthCheck thread
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
    }
  }
}
