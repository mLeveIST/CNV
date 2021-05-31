package pt.ulisboa.tecnico.cnv.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class InstanceController {
    private static final int MIN_INSTANCES = 1;
    private static final int MAX_INSTANCES = 10;
    private static final String TABLE_NAME = "Requests_Info";

    private static final String GRID_SCAN = "GRID_SCAN";
    private static final String GREEDY_RANGE_SCAN = "GREEDY_RANGE_SCAN";
    private static final String PROGRESSIVE_SCAN = "PROGRESSIVE_SCAN";

    private static ControllerArgumentParser controllerArgumentParser;

    private static AmazonCloudWatch cloudWatch;
    private static DynamoDB dynamoDB;
    private static AmazonEC2 ec2;

    private static Map<String, InstanceInfo> instances;


    private static void initControllerServices(String[] args) {
        controllerArgumentParser = new ControllerArgumentParser(args);
        System.out.println("> Parsed server arguments...");
    }

    private static void initAWSServices() {
        AWSCredentials credentials = new ProfileCredentialsProvider().getCredentials();

        cloudWatch = AmazonCloudWatchClientBuilder.standard()
                .withRegion("us-east-1")
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();

        dynamoDB = new DynamoDB(AmazonDynamoDBClientBuilder.standard()
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .withRegion("us-east-1")
                .build());

        ec2 = AmazonEC2ClientBuilder.standard()
                .withRegion("us-east-1")
                .withCredentials(new AWSStaticCredentialsProvider(credentials))
                .build();
    }

    // Auto Scaler code

    private static void startAutoScaler() throws InterruptedException {
        createInstance();

        Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    checkCPUUsage();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private static void createInstance() throws InterruptedException {
        try {
            RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

            runInstancesRequest.withImageId("TODO_IN_AWS")
                    .withInstanceType("t2.micro")
                    .withMinCount(MIN_INSTANCES)
                    .withMaxCount(MIN_INSTANCES)
                    .withKeyName("CNV-Lab-AWS")
                    .withSecurityGroups("TODO_IN_AWS")
                    .withMonitoring(true);

            RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);

            Instance instance = runInstancesResult.getReservation().getInstances().get(0);

            System.out.println("[AS] > Created instance with ID " + instance.getInstanceId());

            while (!instance.getState().getName().equals("running")) {
                System.out.println("[AS] > Waiting for instance with ID " + instance.getInstanceId());
                Thread.sleep(2000);
            }

            System.out.println("[AS] > Running instance with ID " + instance.getInstanceId());

            instances.put(instance.getInstanceId(), new InstanceInfo(instance));

        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Response Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    private static void checkCPUUsage() throws InterruptedException {
        double totalAverageCPU = 0.0;
        double instanceAverageCPU;
        int numDataPoints;
        InstanceInfo lowestInstance = null;
        int numInstances = 0;

        if (instances.size() == 0)
            return;

        Dimension instanceDimension = new Dimension();
        instanceDimension.setName("InstanceId");

        for (InstanceInfo instance : instances.values()) {

            if (!instance.isRunning() || instance.isPendingTermination())
                continue;

            numInstances++;
            instanceDimension.setValue(instance.getInstanceId());

            GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                    .withStartTime(new Date(new Date().getTime() - 60000))
                    .withNamespace("AWS/EC2")
                    .withPeriod(60)
                    .withMetricName("CPUUtilization")
                    .withStatistics("Average")
                    .withDimensions(instanceDimension)
                    .withEndTime(new Date());

            List<Datapoint> dataPoints = cloudWatch.getMetricStatistics(request).getDatapoints();

            instanceAverageCPU = 0.0;
            numDataPoints = 0;

            for (Datapoint dp : dataPoints) {
                System.out.println(
                        "[AS] > CPU utilization for instance " + instance.getInstanceId() +
                        " = " + dp.getAverage() +
                        "\nTimestamp: " + dp.getTimestamp() +
                        "\nMax: " + dp.getMaximum() +
                        "\nSampleCount: " + dp.getSampleCount() +
                        "\nUnit: " + dp.getUnit());

                instanceAverageCPU += dp.getAverage();
                numDataPoints++;
            }

            if (numDataPoints == 0) {
                System.out.println("[AS] > WARNING : No data points for instance " + instance.getInstanceId());
                continue;
            }

            instanceAverageCPU = instanceAverageCPU / numDataPoints;
            totalAverageCPU += instanceAverageCPU;
            instance.setCpuUsage(instanceAverageCPU);

            if (lowestInstance == null || lowestInstance.getCpuUsage() > instanceAverageCPU) {
                lowestInstance = instance;
            }
        }

        if (numInstances != 0) {
            totalAverageCPU = totalAverageCPU / numInstances;

            if (totalAverageCPU >= 0.8) {
                createInstance();
            } else if (numInstances > 1 && totalAverageCPU <= 0.2 && lowestInstance != null) {
                System.out.println("[AS] > Flagging to Terminate instance " + lowestInstance.getInstanceId());
                lowestInstance.setPendingTermination();
                terminateInstance(lowestInstance);
            }
        }
    }

    private static void terminateInstance(final InstanceInfo instanceInfo) {
        if (instanceInfo == null) {
            System.out.println("[AST] > ERROR : Instance to terminate is NULL");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (instanceInfo.getNumberOfPendingRequests() != 0) {
                        System.out.println("[AST] > Waiting for requests on instance " + instanceInfo.getInstanceId());
                        Thread.sleep(5000);
                    }

                    System.out.println("[AST] > Terminating instance " + instanceInfo.getInstanceId());

                    TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
                    termInstanceReq.withInstanceIds(instanceInfo.getInstanceId());
                    ec2.terminateInstances(termInstanceReq);

                    while (!instanceInfo.isTerminated()) {
                        System.out.println("[AST] > Waiting for termination of instance " + instanceInfo.getInstanceId());
                        Thread.sleep(5000);
                    }

                    System.out.println("[AST] > Instance " + instanceInfo.getInstanceId() + " terminated");

                    instances.remove(instanceInfo.getInstanceId());

                } catch (InterruptedException ie) {
                    ie.printStackTrace();
                } catch (AmazonServiceException ase) {
                    System.out.println("Caught Exception: " + ase.getMessage());
                    System.out.println("Response Status Code: " + ase.getStatusCode());
                    System.out.println("Error Code: " + ase.getErrorCode());
                    System.out.println("Request ID: " + ase.getRequestId());
                }
            }
        }).start();
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
                            System.out.println("[HC] > Ping to " + instance.getInstanceId() + " Failed");
                            instance.addFailedPing();
                        } else if (currHealthStatus > 0) {
                            System.out.println("[HC] > Ping to " + instance.getInstanceId() + " Successful");
                            instance.removeFailedPing();
                        }

                        if (instance.isUnhealthy()) {
                            instance.setPendingTermination();
                            terminateInstance(instance);
                        }

                    } catch (MalformedURLException e) {
                        System.out.println("[HC] > ERROR : Malformed URL!");
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
            System.out.println("[HC] > ERROR : Connection Timed out!");
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
            int x0 = 0, x1 = 0, y0 = 0, y1 = 0;

            String strategy = GRID_SCAN;

            System.out.println(String.format("[LB-%s] > Started thread %s", threadId, threadId));

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

            int workloadLevel = getRequestComplexity(strategy, x1-x0, y1-y0);
            InstanceInfo instanceInfo = instanceToSendTo();
            instanceInfo.addPendingRequest(String.format("%d:%s", threadId, query), workloadLevel);

            HttpURLConnection connection = null;

            try {
                URL url = new URL("http://" + instanceInfo.getInstanceIp() + ":8000/scan?" + query);

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);

                int status = connection.getResponseCode();

                System.out.println(String.format("[LB-%s] > Connection established", threadId));

                if (status == HttpURLConnection.HTTP_OK) {
                    final InputStream in = connection.getInputStream();
                    final OutputStream os = t.getResponseBody();

                    t.sendResponseHeaders(200, 0);

                    int readBytes;
                    byte[] buffer = new byte[4096];

                    while ((readBytes = in.read(buffer)) != -1) {
                        os.write(buffer, 0, readBytes);
                    }

                    os.close();

                    System.out.println(String.format("[LB-%s] > Response sent", threadId));
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

        private static int getRequestComplexity(String strategy, int width, int height) {
            try {
                Table table = dynamoDB.getTable(TABLE_NAME);
                int margin = strategy.equals(GRID_SCAN) ? 20 : 40;

                QuerySpec spec = new QuerySpec()
                        .withKeyConditionExpression("Strategy = :v_strategy and Area between :v_area_min and :v_area_max")
                        .withValueMap(new ValueMap()
                                .withString(":v_strategy", strategy)
                                .withNumber(":v_area_min", (width - margin) * (height - margin))
                                .withNumber(":v_area_max", (width + margin) * (height + margin)));

                ItemCollection<QueryOutcome> items = table.query(spec);

                int area = width * height;
                int diffAreas, approxArea = area;
                Item itemToLockup = null;

                for (Item item : items) {
                    System.out.println(item.toJSONPretty());
                    diffAreas = Math.abs(area - item.getInt("Area"));

                    if (diffAreas < approxArea) {
                        approxArea = diffAreas;
                        itemToLockup = item;
                    }
                }

                return itemToLockup != null ? itemToLockup.getInt("Complexity") : getDefaultRequestComplexity(strategy, area);

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

            return 0;
        }

        private static int getDefaultRequestComplexity(String strategy, int area) {
            if (area <= 10000) {
                return 1;
            } else if (area <= 100000) {
                return 2;
            } else if (area <= 150000) {
                return 3;
            } else if (area <= 250000) {
                return 4;
            } else if (area <= 350000) {
                return 5;
            } else if (area <= 500000) {
                return 6;
            } else if (area <= 800000) {
                return 7;
            } else if (area <= 1000000) {
                return 8;
            } else if (area <= 2000000) {
                return 9;
            } else {
                return 10;
            }
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
