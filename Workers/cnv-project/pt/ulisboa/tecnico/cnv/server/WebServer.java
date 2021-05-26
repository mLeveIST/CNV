package pt.ulisboa.tecnico.cnv.server;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.*;

import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import pt.ulisboa.tecnico.cnv.solver.Solver;
import pt.ulisboa.tecnico.cnv.solver.SolverFactory;

import javax.imageio.ImageIO;


public class WebServer {

  static ServerArgumentParser sap = null;

  private static Map<Long, Statistics> statistics;

  static AmazonDynamoDB dynamoDB;

  public static void main(final String[] args) throws Exception {

    try {
      WebServer.sap = new ServerArgumentParser(args);
    }
    catch (Exception e) {
      System.out.println(e);
      return;
    }

    statistics = new ConcurrentHashMap<>();

    System.out.println("> Finished parsing Server args.");

    final HttpServer server = HttpServer.create(new InetSocketAddress(WebServer.sap.getServerAddress(), WebServer.sap.getServerPort()), 0);

    server.createContext("/scan", new MyHandler());
    server.setExecutor(Executors.newCachedThreadPool());
    server.start();

    System.out.println(server.getAddress().toString());
  }

  static class MyHandler implements HttpHandler {

    @Override
    public void handle(final HttpExchange t) throws IOException {

      final String query = t.getRequestURI().getQuery();

      if (query == null) {
        final String response = "ping";

        t.sendResponseHeaders(200, 0);
        OutputStream pingOs = t.getResponseBody();
        pingOs.write(response.getBytes());
        pingOs.close();

        System.out.println("> Sent response to " + t.getRemoteAddress().toString());
      } else {
        Statistics ss = new Statistics(query);

        statistics.put(Thread.currentThread().getId(), new Statistics(query));

        System.out.println("> Query:\t" + query);

        final String[] params = query.split("&");
        final ArrayList<String> newArgs = new ArrayList<>();

        for (final String p : params) {
          final String[] splitParam = p.split("=");

          if (splitParam[0].equals("i")) {
            splitParam[1] = WebServer.sap.getMapsDirectory() + "/" + splitParam[1];
          }

          newArgs.add("-" + splitParam[0]);
          newArgs.add(splitParam[1]);
        }

        if (sap.isDebugging()) {
          newArgs.add("-d");
        }

        final String[] args = new String[newArgs.size()];
        int i = 0;

        for (String arg : newArgs) {
          args[i] = arg;
          i++;
        }

        final Solver s = SolverFactory.getInstance().makeSolver(args);

        if (s == null) {
          System.out.println("> Problem creating Solver. Exiting.");
          System.exit(1);
        }

        File responseFile = null;
        try {
          final BufferedImage outputImg = s.solveImage();
          final String outPath = WebServer.sap.getOutputDirectory();
          final String imageName = s.toString();
          final Path imagePathPNG = Paths.get(outPath, imageName);

          ImageIO.write(outputImg, "png", imagePathPNG.toFile());

          responseFile = imagePathPNG.toFile();

        } catch (final FileNotFoundException e) {
          e.printStackTrace();
        } catch (final IOException e) {
          e.printStackTrace();
        } catch (ClassNotFoundException e) {
          e.printStackTrace();
        } catch (Exception e) {
          e.printStackTrace();
        }

        final Headers hdrs = t.getResponseHeaders();

        hdrs.add("Content-Type", "image/png");
        hdrs.add("Access-Control-Allow-Origin", "*");
        hdrs.add("Access-Control-Allow-Credentials", "true");
        hdrs.add("Access-Control-Allow-Methods", "POST, GET, HEAD, OPTIONS");
        hdrs.add("Access-Control-Allow-Headers", "Origin, Accept, X-Requested-With, Content-Type, Access-Control-Request-Method, Access-Control-Request-Headers");

        t.sendResponseHeaders(200, responseFile.length());

        final OutputStream os = t.getResponseBody();
        Files.copy(responseFile.toPath(), os);

        os.close();

        /*try {
          File file = new File("./statistics.txt");

          if (!file.exists()) {
            file.createNewFile();
          }

          final PrintWriter writer = new PrintWriter(new BufferedWriter(new FileWriter(file, true)));
          final Statistics currStatistics = statistics.get(Thread.currentThread().getId());

          writer.println(currStatistics.getQuery());
          writer.println("Method Calls: " + currStatistics.getMCount());
          writer.println("Basic Blocks Traced: " + currStatistics.getBBCount());
          writer.println("Instructions Executed: " + currStatistics.getICount());
          writer.println("Field Loads: " + currStatistics.getFLCount());
          writer.println("Field Stores: " + currStatistics.getFSCount());
          writer.println("Loads: " + currStatistics.getLCount());
          writer.println("Stores: " + currStatistics.getSCount());
          writer.println("New Allocations: " + currStatistics.getNCount());
          writer.println("New Array Allocations: " + currStatistics.getNACount());
          writer.println("'A' New Array Allocations: " + currStatistics.getANACount());
          writer.println("Multi 'A' New Array Allocations: " + currStatistics.getMANACount());

          writer.close();

        } catch (Exception e) {
          System.out.println("Error!!!!");
        }*/

        try {
            String tableName = "ec2-stats";

            Table table = dynamoDB.getTable(tableName);

            final Statistics currStatistics = statistics.get(Thread.currentThread().getId());

            Map<String, AttributeValue> item = newItem( , , , currStatistics.getBBCount());
            PutItemRequest putItemRequest = new PutItemRequest(tableName, item);
            PutItemResult putItemResult = dynamoDB.putItem(putItemRequest);
            System.out.println("Result: " + putItemResult);*/

        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it "
                    + "to AWS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered "
                    + "a serious internal problem while trying to communicate with AWS, "
                    + "such as not being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        }
    }
        statistics.remove(Thread.currentThread().getId());

        System.out.println("> Sent response to " + t.getRemoteAddress().toString());
      }
    }
  }

  public static synchronized void countMethods(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addMCount();
  }

  public static synchronized void countBasicBlocks(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addBBCount();
  }

  public static synchronized void countInstructions(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addICount(toAdd);
  }

  public static synchronized void countFieldLoads(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addFLCount();
  }

  public static synchronized void countFieldStores(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addFSCount();
  }

  public static synchronized void countLoads(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addLCount();
  }

  public static synchronized void countStores(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addSCount();
  }

  public static synchronized void countNews(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addNCount();
  }

  public static synchronized void countNewArrays(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addNACount();
  }

  public static synchronized void countANewArrays(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addANACount();
  }

  public static synchronized void countMultiANewArrays(int toAdd) {
    statistics.get(Thread.currentThread().getId()).addMANACount();
  }

  private static void initDB() throws Exception {
    /*
      * (~/.aws/credentials).
      */
    ProfileCredentialsProvider credentialsProvider = new ProfileCredentialsProvider();
    try {
        credentialsProvider.getCredentials();
    } catch (Exception e) {
        throw new AmazonClientException(
                "Cannot load the credentials from the credential profiles file. " +
                "Make sure that your credentials file is at the correct " +
                "location (~/.aws/credentials).",
                e);
    }
    dynamoDB = AmazonDynamoDBClientBuilder.standard()
        .withCredentials(credentialsProvider)
        .withRegion("us-east-1")
        .build();
  }

  private static Map<String, AttributeValue> newItem(int id, int viewport, String map, int stat) {
    Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
    item.put("id", new AttributeValue().withN(Integer.toString(id)));
    item.put("viewport", new AttributeValue().withN(Integer.toString(viewport)));
    item.put("map", new AttributeValue(map));
    item.put("stat", new AttributeValue().withN(Integer.toString(stat)));

    return item;
  }

}
