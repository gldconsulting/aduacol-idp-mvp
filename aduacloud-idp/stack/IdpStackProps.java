package com.aduacloud.infra.types;

import com.aduacloud.infra.config.Aduacloud;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.s3.IBucket;
import software.amazon.awscdk.services.sqs.IQueue;

import java.util.Objects;

public class IdpStackProps extends BaseStackProps {

    private final IBucket inputBucket;
    private final IBucket workingBucket;
    private final IBucket outputBucket;
    private final IBucket lambdaArtifactsBucket;
    private final String lambdaArtifactsBucketName;
    private final IQueue documentQueue;
    private final IQueue notificationQueue;

    private IdpStackProps(Builder builder) {
        super(builder.config, builder.stackProps);
        this.inputBucket = Objects.requireNonNull(builder.inputBucket);
        this.workingBucket = Objects.requireNonNull(builder.workingBucket);
        this.outputBucket = Objects.requireNonNull(builder.outputBucket);
        this.lambdaArtifactsBucket = Objects.requireNonNull(builder.lambdaArtifactsBucket);
        this.lambdaArtifactsBucketName = Objects.requireNonNull(builder.lambdaArtifactsBucketName);
        this.documentQueue = Objects.requireNonNull(builder.documentQueue);
        this.notificationQueue = Objects.requireNonNull(builder.notificationQueue);
    }

    public static Builder builder() { return new Builder(); }

    public IBucket getInputBucket() { return inputBucket; }
    public IBucket getWorkingBucket() { return workingBucket; }
    public IBucket getOutputBucket() { return outputBucket; }
    public IBucket getLambdaArtifactsBucket() { return lambdaArtifactsBucket; }
    public String getLambdaArtifactsBucketName() { return lambdaArtifactsBucketName; }
    public IQueue getDocumentQueue() { return documentQueue; }
    public IQueue getNotificationQueue() { return notificationQueue; }

    public static class Builder {
        private Aduacloud config;
        private StackProps stackProps;
        private IBucket inputBucket;
        private IBucket workingBucket;
        private IBucket outputBucket;
        private IBucket lambdaArtifactsBucket;
        private String lambdaArtifactsBucketName;
        private IQueue documentQueue;
        private IQueue notificationQueue;

        private Builder() {}

        public Builder config(Aduacloud config) { this.config = config; return this; }
        public Builder stackProps(StackProps stackProps) { this.stackProps = stackProps; return this; }
        public Builder inputBucket(IBucket bucket) { this.inputBucket = bucket; return this; }
        public Builder workingBucket(IBucket bucket) { this.workingBucket = bucket; return this; }
        public Builder outputBucket(IBucket bucket) { this.outputBucket = bucket; return this; }
        public Builder lambdaArtifactsBucket(IBucket bucket) { this.lambdaArtifactsBucket = bucket; return this; }
        public Builder lambdaArtifactsBucketName(String bucketName) { this.lambdaArtifactsBucketName = bucketName; return this; }
        public Builder documentQueue(IQueue queue) { this.documentQueue = queue; return this; }
        public Builder notificationQueue(IQueue queue) { this.notificationQueue = queue; return this; }

        public IdpStackProps build() {
            Objects.requireNonNull(config);
            Objects.requireNonNull(stackProps);
            return new IdpStackProps(this);
        }
    }
}
