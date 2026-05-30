package com.ahu.ticket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.sentinel")
public class SentinelRuleProperties {

    private EndpointRule queryTrain = new EndpointRule();

    private EndpointRule bookTicket = new EndpointRule();

    public SentinelRuleProperties() {
        queryTrain.setInterfaceQps(200);
        queryTrain.setDefaultPerTrainQps(30);
        queryTrain.getHotTrainQps().put("G1", 10);
        queryTrain.getHotTrainQps().put("D1", 12);

        bookTicket.setInterfaceQps(80);
        bookTicket.setDefaultPerTrainQps(20);
        bookTicket.getHotTrainQps().put("G1", 6);
        bookTicket.getHotTrainQps().put("D1", 8);
    }

    public EndpointRule getQueryTrain() {
        return queryTrain;
    }

    public void setQueryTrain(EndpointRule queryTrain) {
        this.queryTrain = queryTrain;
    }

    public EndpointRule getBookTicket() {
        return bookTicket;
    }

    public void setBookTicket(EndpointRule bookTicket) {
        this.bookTicket = bookTicket;
    }

    public static class EndpointRule {
        private double interfaceQps;
        private double defaultPerTrainQps;
        private Map<String, Integer> hotTrainQps = new LinkedHashMap<>();

        public double getInterfaceQps() {
            return interfaceQps;
        }

        public void setInterfaceQps(double interfaceQps) {
            this.interfaceQps = interfaceQps;
        }

        public double getDefaultPerTrainQps() {
            return defaultPerTrainQps;
        }

        public void setDefaultPerTrainQps(double defaultPerTrainQps) {
            this.defaultPerTrainQps = defaultPerTrainQps;
        }

        public Map<String, Integer> getHotTrainQps() {
            return hotTrainQps;
        }

        public void setHotTrainQps(Map<String, Integer> hotTrainQps) {
            this.hotTrainQps = hotTrainQps;
        }
    }
}
