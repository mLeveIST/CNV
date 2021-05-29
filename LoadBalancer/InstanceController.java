package pt.ulisboa.tecnico.cnv.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.Instance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class InstanceController {
    private static ControllerArgumentParser controllerArgumentParser;

    private static AmazonCloudWatch cloudWatch;
    private static AmazonDynamoDB dynamoDB;
    private static AmazonEC2 ec2;

    private static Map<String, InstanceInfo> instances;


    private static void initControllerServices(String[] args) {
        controllerArgumentParser = new ControllerArgumentParser(args);
        System.out.println("> Parsed server arguments...");
    }

    // TODO : Merge Manuel and Ana
    private static void initAWSServices() {
        AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();

        cloudWatch = AmazonCloudWatchClientBuilder.standard()
                .withRegion("us-east-1")
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();

        dynamoDB = AmazonDynamoDBClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withRegion("us-east-1")
                .build();

        ec2 = AmazonEC2ClientBuilder.standard()
                .withRegion("us-east-1")
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
    }

    // Auto Scaler code

    // TODO : Merge Manuel
    private static void startAutoScaler() {
        createInstance();

        Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                checkCPUUsage();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    // TODO : Merge Manuel
    private static void createInstance() {
        Instance instance = null;
        instances.put(instance.getInstanceId(), new InstanceInfo(instance));
    }

    // TODO : Merge Manuel
    private static void checkCPUUsage() {

    }

    // TODO : Merge Manuel
    private static void deleteInstance() {

    }

    // Load Balancer Code

    private static void startLoadBalancer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(
                controllerArgumentParser.getServerAddress(),
                controllerArgumentParser.getServerPort()), 0);

        server.createContext("/scan", new ScanHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        startHealthChecker();
    }

    private static void startHealthChecker() {
        Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                for (InstanceInfo instance : instances.values()) {
                    try {
                        URL url = new URL("http://" + instance.getInstanceIp() + ":8000/scan");

                        int currHealthStatus = instance.getFailedPings();

                        if (pingInstance(url) != HttpURLConnection.HTTP_OK) {
                            instance.addFailedPing();
                        } else if (currHealthStatus > 0) {
                            instance.removeFailedPing();
                        }

                        if (instance.isUnhealthy()) {
                            instance.setPendingTermination();
                        }

                    } catch (MalformedURLException e) {
                        System.out.println("> ERROR : Malformed URL!");
                    }
                }
            }
        }, 120, 30, TimeUnit.SECONDS);
    }

    private static int pingInstance(URL url) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);

            return connection.getResponseCode();

        } catch(SocketTimeoutException ste) {
            System.out.println("> ERROR : Connection Timed out!");
            return HttpURLConnection.HTTP_CLIENT_TIMEOUT;
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return HttpURLConnection.HTTP_UNAVAILABLE;
        } finally {
            Objects.requireNonNull(connection).disconnect();
        }
    }

    public static void main(String[] args) {
        try {
            initControllerServices(args);
            initAWSServices();

            instances = new ConcurrentHashMap<>();

            startAutoScaler();
            startLoadBalancer();

        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Response Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    static class ScanHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {
            final long threadId = Thread.currentThread().getId();
            final String query = t.getRequestURI().getQuery();
            final String[] queryPairs = query.split("&");
            int x0, x1, y0, y1;

            String strategy = null;
            Integer scanAreaSize = null;

            for (String queryPair : queryPairs) {
                String[] queryParameter = queryPair.split("=");

                switch (queryParameter[0]) {
                    case "s":
                        strategy = queryParameter[1];
                        break;
                    case "x0":
                        x0 = Integer.parseInt(queryParameter[1]);
                        break;
                    case "x1":
                        x1 = Integer.parseInt(queryParameter[1]);
                        break;
                    case "y0":
                        y0 = Integer.parseInt(queryParameter[1]);
                        break;
                    case "y1":
                        y1 = Integer.parseInt(queryParameter[1]);
                        break;
                }
            }

            int workloadLevel = getWorkloadLevel();
            InstanceInfo instanceInfo = instanceToSendTo();
            instanceInfo.addPendingRequest(String.format("%d:%s", threadId, query), workloadLevel);

            HttpURLConnection connection = null;

            try {
                URL url = new URL("http://" + instanceInfo.getInstanceIp() + ":8000/scan?" + query);

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

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
                mue.printStackTrace();
            } catch (IOException ioe) {
                ioe.printStackTrace();
            } finally {
                connection.disconnect();
                instanceInfo.removePendingRequest(String.format("%d:%s", threadId, query));
            }
        }

        // TODO : Merge Ana
        private static int getWorkloadLevel() {
            return 0;
        }

        private static InstanceInfo instanceToSendTo() {
            int minWorkloadLevel = Integer.MAX_VALUE;
            InstanceInfo instance = null;

            for (InstanceInfo instanceInfo : instances.values()) {
                if (instanceInfo.isPendingTermination())
                    continue;

                if (instanceInfo.getInstanceWorkload() < minWorkloadLevel) {
                    instance = instanceInfo;
                }
            }

            return instance;
        }
    }
}
