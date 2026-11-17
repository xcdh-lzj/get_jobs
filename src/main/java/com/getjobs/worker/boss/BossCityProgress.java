package com.getjobs.worker.boss;

import java.util.List;

public record BossCityProgress(int currentIndex, boolean autoSwitch, int windowSize, int maxNewJobs, List<City> cities) {
    public record City(String code,String name,String state,int processed,int newJobs,int uncertain,
                       int lowYieldKeywords,int windowCount,int windowNewJobs,int windowUncertain,String reason) {}
}
