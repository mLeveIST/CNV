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

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;

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

    private static DynamoDB dynamoDB;

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
                final String[] params = query.split("&");

                int x0 = 0;
                int x1 = 0;
                int y0 = 0;
                int y1 = 0;

                String strategy = null;

                statistics.put(Thread.currentThread().getId(), new Statistics(query));

                System.out.println("> Query:\t" + query);

                final ArrayList<String> newArgs = new ArrayList<>();

                for (final String p : params) {
                    final String[] splitParam = p.split("=");

                    if (splitParam[0].equals("i")) {
                        splitParam[1] = WebServer.sap.getMapsDirectory() + "/" + splitParam[1];
                    }

                    newArgs.add("-" + splitParam[0]);
                    newArgs.add(splitParam[1]);

                    switch (splitParam[0]) {
                        case "s":
                            strategy = splitParam[1];
                            break;
                        case "x0":
                            x0 = Integer.parseInt(splitParam[1]);
                            break;
                        case "x1":
                            x1 = Integer.parseInt(splitParam[1]);
                            break;
                        case "y0":
                            y0 = Integer.parseInt(splitParam[1]);
                            break;
                        case "y1":
                            y1 = Integer.parseInt(splitParam[1]);
                            break;
                    }
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

                long startMakingSolver;
                long timeMakingSolver;
                long startSolvingImage;
                long timeSolvingImage = -1;

                startMakingSolver = System.currentTimeMillis();
                final Solver s = SolverFactory.getInstance().makeSolver(args);
                timeMakingSolver = System.currentTimeMillis() - startMakingSolver;

                if (s == null) {
                    System.out.println("> Problem creating Solver. Exiting.");
                    System.exit(1);
                }

                File responseFile = null;
                try {
                    startSolvingImage = System.currentTimeMillis();
                    final BufferedImage outputImg = s.solveImage();
                    timeSolvingImage = System.currentTimeMillis() - startSolvingImage;

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

                try {
                    File file = new File("./times.txt");

                    if (!file.exists()) {
                        file.createNewFile();
                    }

                    final PrintWriter writerT = new PrintWriter(new BufferedWriter(new FileWriter(file, true)));

                    writerT.println(timeMakingSolver + " + " + timeSolvingImage + " = " + (timeMakingSolver + timeSolvingImage));
                    writerT.close();

                } catch (Exception e) {
                    System.out.println("Error!!!!");
                }

                try {
                    initDB();
                    String tableName = "Requests_Info";

                    Table table = dynamoDB.getTable(tableName);

                    final Statistics currStatistics = statistics.get(Thread.currentThread().getId());

                    int BBCount = currStatistics.getBBCount();

                    int complexity;
                    if (BBCount <= 300) {
                        complexity = 1;
                    } else if (BBCount <= 500) {
                        complexity = 2;
                    } else if (BBCount <= 1000) {
                        complexity = 3;
                    } else if (BBCount <= 1500) {
                        complexity = 4;
                    } else if (BBCount <= 2000) {
                        complexity = 5;
                    } else if (BBCount <= 2500) {
                        complexity = 6;
                    } else if (BBCount <= 3000) {
                        complexity = 7;
                    } else if (BBCount <= 3500) {
                        complexity = 8;
                    } else if (BBCount <= 4000) {
                        complexity = 9;
                    } else {
                        complexity = 10;
                    }

                    Item item = newItem(strategy, (x1 - x0) * (y1 - y0), complexity);
                    table.putItem(item);

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

    private static void initDB(){
        AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();

        dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withRegion("us-east-1")
                .build());
    }

    private static Item newItem(String algorithm, int area, int complexity) {

        return new Item()
                .withString("Strategy", algorithm)
                .withInt("Area", area)
                .withInt("Complexity", complexity);
    }
}
