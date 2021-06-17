package pt.ulisboa.tecnico.cnv.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.net.*;

import java.util.*;
import java.util.concurrent.*;

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
    private static final int BURST_MIN_SIZE = 5;
    private static final String TABLE_NAME = "Requests_Info";

    private static final String GRID_SCAN = "GRID_SCAN";
    private static final String GREEDY_RANGE_SCAN = "GREEDY_RANGE_SCAN";
    private static final String PROGRESSIVE_SCAN = "PROGRESSIVE_SCAN";

    private static ControllerArgumentParser controllerArgumentParser;

    private static AmazonCloudWatch cloudWatch;
    private static DynamoDB dynamoDB;
    private static AmazonEC2 ec2;

    private static Map<String, InstanceInfo> instances;
    private static ThreadPoolExecutor threadPoolExecutor;


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
                if (threadPoolExecutor != null && threadPoolExecutor.getQueue().size() >= BURST_MIN_SIZE) {
                    try {
                        createInstance();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }, 120, 1, TimeUnit.SECONDS);

        Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    checkCPUUsage();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, 120, 60, TimeUnit.SECONDS);

        Executors.newScheduledThreadPool(1).scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {

                for (InstanceInfo instance : instances.values()) {
                    if (!instance.isPendingTermination()) {
                        continue;
                    }

                    if (instances.size() <= MIN_INSTANCES) {
                        System.out.println("[AST] > NOTICE : Sole instance flagged to terminate, creating new one");

                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    createInstance();
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                            }
                        }).start();
                    }

                    terminateInstance(instance);
                }
            }
        }, 120, 20, TimeUnit.SECONDS);
    }

    private static void createInstance() throws InterruptedException {
        try {
            RunInstancesRequest runInstancesRequest = new RunInstancesRequest();

            runInstancesRequest.withImageId("ami-0b2ed5c67c334aeca")
                    .withInstanceType("t2.micro")
                    .withMinCount(1)
                    .withMaxCount(1)
                    .withKeyName("CNV-Lab-AWS")
                    .withSecurityGroups("CNV-SSH-HTTP")
                    .withMonitoring(true);

            RunInstancesResult runInstancesResult = ec2.runInstances(runInstancesRequest);
            Instance instance = runInstancesResult.getReservation().getInstances().get(0);

            String instanceId = instance.getInstanceId();

            System.out.println("[AS] > Created instance with ID " + instanceId);

            while (!isInstanceRunning(instanceId)) {
                System.out.println("[AS] > Waiting for instance with ID " + instanceId);
                Thread.sleep(10000);
            }

            System.out.println("[AS] > Running instance with ID " + instance.getInstanceId());

            instances.put(instanceId, new InstanceInfo(instanceId, getInstanceIp(instanceId)));

        } catch (AmazonServiceException ase) {
            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Response Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());
        }
    }

    private synchronized static String getInstanceIp(String instanceId) {
        DescribeInstancesResult describeInstancesResult = ec2.describeInstances();
        List<Reservation> reservations = describeInstancesResult.getReservations();
        Set<Instance> instances = new HashSet<>();

        for (Reservation reservation : reservations) {
            instances.addAll(reservation.getInstances());
        }

        for (Instance instance : instances) {
            if (instance.getInstanceId().equals(instanceId)) {
                return instance.getPublicDnsName();
            }
        }

        System.out.println("[AS] > WARNING: Could not get DNS name for instance with ID " + instanceId);

        return "";
    }

    private synchronized static boolean isInstanceRunning(String instanceId) {
        DescribeInstanceStatusResult describeInstanceStatusResult = ec2.describeInstanceStatus(new DescribeInstanceStatusRequest()
                .withInstanceIds(instanceId)
                .withIncludeAllInstances(true));

        if (describeInstanceStatusResult.getInstanceStatuses().size() == 0) {
            return false;
        }

        return describeInstanceStatusResult.getInstanceStatuses().get(0).getInstanceState().getName().equals("running");
    }

    private synchronized static boolean isInstanceTerminated(String instanceId) {
        DescribeInstanceStatusResult describeInstanceStatusResult = ec2.describeInstanceStatus(new DescribeInstanceStatusRequest()
                .withInstanceIds(instanceId)
                .withIncludeAllInstances(true));

        if (describeInstanceStatusResult.getInstanceStatuses().size() == 0) {
            return false;
        }

        return describeInstanceStatusResult.getInstanceStatuses().get(0).getInstanceState().getName().equals("terminated");
    }

    private static void checkCPUUsage() throws InterruptedException {
        double totalAverageCPU = 0.0;
        double instanceAverageCPU;
        int numDataPoints;
        InstanceInfo lowestInstance = null;
        int numInstances = 0;

        if (instances.size() == 0)
            return;

        System.out.println("[AS] > Checking CPU usage of instances");

        Dimension instanceDimension = new Dimension();
        instanceDimension.setName("InstanceId");

        for (InstanceInfo instance : instances.values()) {

            if (!isInstanceRunning(instance.getInstanceId()) || instance.isPendingTermination())
                continue;

            numInstances++;
            instanceDimension.setValue(instance.getInstanceId());

            long endTime = System.currentTimeMillis();
            long startTime = endTime - 120000;

            GetMetricStatisticsRequest request = new GetMetricStatisticsRequest()
                    .withStartTime(new Date(startTime))
                    .withNamespace("AWS/EC2")
                    .withPeriod(60)
                    .withMetricName("CPUUtilization")
                    .withStatistics("Average")
                    .withDimensions(instanceDimension)
                    .withEndTime(new Date(endTime));

            List<Datapoint> dataPoints = cloudWatch.getMetricStatistics(request).getDatapoints();

            instanceAverageCPU = 0.0;
            numDataPoints = 0;

            for (Datapoint dp : dataPoints) {
                System.out.println("[AS] > CPU utilization for instance " + instance.getInstanceId() + " = " + dp.getAverage());

                instanceAverageCPU += dp.getAverage();
                numDataPoints++;
            }

            if (numDataPoints == 0) {
                System.out.println("[AS] > NOTICE : No data points for instance " + instance.getInstanceId());
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

            System.out.println("[AS] > Total CPU Average = " + totalAverageCPU);

            if (totalAverageCPU >= 80.0) {
                createInstance();
            } else if (numInstances > 1 && totalAverageCPU <= 20.0 && lowestInstance != null) {
                System.out.println("[AS] > Flagging to Terminate instance " + lowestInstance.getInstanceId());
                lowestInstance.setPendingTermination();
            }
        }
    }

    private static void terminateInstance(final InstanceInfo instanceInfo) {
        if (instanceInfo == null) {
            System.out.println("[AST] > ERROR : Instance to terminate is NULL");
            return;
        }

        try {
            while (instanceInfo.getNumberOfPendingRequests() != 0) {
                System.out.println("[AST] > Waiting for requests on instance " + instanceInfo.getInstanceId() + " to finish");
                Thread.sleep(10000);
            }

            System.out.println("[AST] > Terminating instance " + instanceInfo.getInstanceId());

            TerminateInstancesRequest termInstanceReq = new TerminateInstancesRequest();
            termInstanceReq.withInstanceIds(instanceInfo.getInstanceId());
            ec2.terminateInstances(termInstanceReq);

            while (!isInstanceTerminated(instanceInfo.getInstanceId())) {
                System.out.println("[AST] > Waiting for termination of instance " + instanceInfo.getInstanceId());
                Thread.sleep(10000);
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

    // Load Balancer Code

    private static void startLoadBalancer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(
                controllerArgumentParser.getServerAddress(),
                controllerArgumentParser.getServerPort()), 0);

        server.createContext("/scan", new ScanHandler());
        server.setExecutor(InstanceController.newCachedThreadPoolWithQueue());
        server.start();

        startHealthChecker();
    }

    private static ThreadPoolExecutor newCachedThreadPoolWithQueue() {
        threadPoolExecutor = new ThreadPoolExecutor(
                10,
                20,
                30,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>()
        );
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        return threadPoolExecutor;
    }

    private static void startHealthChecker() {
        System.out.println("[HC] > Starting Health Checker");

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
                        }

                    } catch (MalformedURLException e) {
                        System.out.println("[HC] > ERROR : Malformed URL!");
                    }
                }
            }
        }, 120, 60, TimeUnit.SECONDS);
    }

    private static int pingInstance(URL url) {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(30000);

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

            /*DescribeInstancesResult describeInstancesResult = ec2.describeInstances();
            List<Reservation> reservations = describeInstancesResult.getReservations();
            Set<Instance> instances = new HashSet<>();

            System.out.println("total reservations = " + reservations.size());
            for (Reservation reservation : reservations) {
                instances.addAll(reservation.getInstances());
            }
            String ip = "";
            for (Instance instance : instances) {
                System.out.println(">>>>>>>>RUN ONCE");
                ip = instance.getPublicDnsName();
            }

            System.out.println(ip);

            try {
                URL url = new URL("http://" + ip + ":8000/scan");

                if (pingInstance(url) != HttpURLConnection.HTTP_OK) {
                    System.out.println("NOT OK");
                }
            } catch (MalformedURLException mue) {
                System.out.println("ERROR");
            } catch (Exception e) {
                System.out.println("ERROR 2");
            }*/

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
        public void handle(HttpExchange t) {
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

            System.out.println("[LB-%s] > Sending request to: " + instanceInfo.getInstanceIp());

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
                Item itemToLookup = null;

                for (Item item : items) {
                    System.out.println("[DB] > Item: " + item.toJSONPretty());
                    diffAreas = Math.abs(area - item.getInt("Area"));

                    if (diffAreas < approxArea) {
                        approxArea = diffAreas;
                        itemToLookup = item;
                    }
                }

                return itemToLookup != null ? itemToLookup.getInt("Complexity") : getDefaultRequestComplexity(strategy, area);

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
            switch (strategy) {
                case GRID_SCAN:
                    return area * 3;
                case GREEDY_RANGE_SCAN:
                    return area * 2;
                default:
                    return area;
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
