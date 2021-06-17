package pt.ulisboa.tecnico.cnv.controller;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InstanceInfo {
    private static final int MAX_FAILED_PINGS = 3;

    private final String instanceId;
    private String instanceIp;

    private Map<String, Integer> pendingRequestsWorkloads;

    private int failedPings;
    private double cpuUsage;
    private boolean pendingTermination;

    public InstanceInfo(String instanceId, String instanceIp) {
        this.instanceId = instanceId;
        this.instanceIp = instanceIp;

        this.pendingRequestsWorkloads = new ConcurrentHashMap<>();

        this.failedPings = 0;
        this.cpuUsage = 0.0;
        this.pendingTermination = false;
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


    // CPU Usage

    public double getCpuUsage() {
        return this.cpuUsage;
    }

    public void setCpuUsage(double cpuUsage) {
        this.cpuUsage = cpuUsage;
    }


    // Termination

    public synchronized boolean isPendingTermination() {
        return this.pendingTermination;
    }

    public synchronized void setPendingTermination() {
        this.pendingTermination = true;
    }
}
