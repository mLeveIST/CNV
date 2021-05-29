package pt.ulisboa.tecnico.cnv.controller;

import com.amazonaws.services.ec2.model.Instance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InstanceInfo {
    private static final int MAX_FAILED_PINGS = 2;

    private final Instance instance;
    private final String instanceId;
    private final String instanceIp;

    private Map<String, Integer> pendingRequestsWorkloads;

    private int failedPings;
    private boolean pendingTermination;

    public InstanceInfo(Instance instance) {
        this.instance = instance;
        this.instanceId = instance.getInstanceId();
        this.instanceIp = instance.getPrivateIpAddress();

        this.pendingRequestsWorkloads = new ConcurrentHashMap<>();

        this.failedPings = 0;
        this.pendingTermination = false;
    }


    // AWS Instance

    public synchronized Instance getInstance() {
        return this.instance;
    }

    public synchronized String getInstanceState() {
        return this.instance.getState().getName();
    }

    public synchronized boolean isRunning() {
        return this.instance.getState().getName().equals("running");
    }


    // Instance ID

    public String getInstanceId() {
        return this.instanceId;
    }


    // Instance IP

    public String getInstanceIp() {
        return this.instanceIp;
    }


    // Instance Pending Requests

    public Map<String, Integer> getPendingRequestsWorkloads() {
        return this.pendingRequestsWorkloads;
    }

    public void setPendingRequestsWorkloads(Map<String, Integer> pendingRequestsWorkloads) {
        this.pendingRequestsWorkloads = pendingRequestsWorkloads;
    }

    public void addPendingRequest(String queryId, int complexity) {
        this.pendingRequestsWorkloads.put(queryId, complexity);
    }

    public void removePendingRequest(String queryId) {
        this.pendingRequestsWorkloads.remove(queryId);
    }

    public int getNumberOfPendingRequests() {
        return this.pendingRequestsWorkloads.size();
    }

    public int getInstanceWorkload() {
        int totalWorkload = 0;

        for (Integer workload : this.pendingRequestsWorkloads.values()) {
            totalWorkload += workload;
        }

        return totalWorkload;
    }


    // Instance Failed Pings

    public synchronized int getFailedPings() {
        return this.failedPings;
    }

    public synchronized void setFailedPings(int failedPings) {
        this.failedPings = failedPings;
    }

    public synchronized void addFailedPing() {
        this.failedPings++;
    }

    public synchronized void removeFailedPing() {
        this.failedPings--;
    }

    public synchronized boolean isUnhealthy() {
        return this.failedPings > MAX_FAILED_PINGS;
    }


    // Termination

    public synchronized boolean isPendingTermination() {
        return this.pendingTermination;
    }

    public synchronized void setPendingTermination() {
        this.pendingTermination = true;
    }
}
