package com.codecompass.retrieve;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 检索配置。权重进配置的理由：T10 调提示词时会频繁动这些数字，调参不该重编译。
 */
@ConfigurationProperties(prefix = "codecompass.retrieve")
public class RetrieveProperties {

    /** 单次检索返回的片段数上限 —— 落实 §07「永远不要把整个仓库塞给大模型」。 */
    private int maxSnippets = 5;

    private Weights weights = new Weights();

    public int getMaxSnippets() {
        return maxSnippets;
    }

    public void setMaxSnippets(int maxSnippets) {
        this.maxSnippets = maxSnippets;
    }

    public Weights getWeights() {
        return weights;
    }

    public void setWeights(Weights weights) {
        this.weights = weights;
    }

    public static class Weights {

        private double className = 2.0;
        private double methodName = 1.0;
        private double fieldOrAnnotation = 0.5;
        private double packageName = 0.2;
        private double anchor = 10.0;
        private double anchorDependency = 3.0;

        public double getClassName() {
            return className;
        }

        public void setClassName(double className) {
            this.className = className;
        }

        public double getMethodName() {
            return methodName;
        }

        public void setMethodName(double methodName) {
            this.methodName = methodName;
        }

        public double getFieldOrAnnotation() {
            return fieldOrAnnotation;
        }

        public void setFieldOrAnnotation(double fieldOrAnnotation) {
            this.fieldOrAnnotation = fieldOrAnnotation;
        }

        public double getPackageName() {
            return packageName;
        }

        public void setPackageName(double packageName) {
            this.packageName = packageName;
        }

        public double getAnchor() {
            return anchor;
        }

        public void setAnchor(double anchor) {
            this.anchor = anchor;
        }

        public double getAnchorDependency() {
            return anchorDependency;
        }

        public void setAnchorDependency(double anchorDependency) {
            this.anchorDependency = anchorDependency;
        }
    }
}
