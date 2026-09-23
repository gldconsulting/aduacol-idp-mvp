package com.aduacloud.infra.stacks;

import com.aduacloud.infra.types.IdpStackProps;
import com.aduacloud.infra.utils.LambdaVersionUtils;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.IRole;
import software.amazon.awscdk.services.iam.ManagedPolicy;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.IFunction;
import software.amazon.awscdk.services.lambda.ILayerVersion;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.LoggingFormat;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.ILogGroup;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.stepfunctions.DefinitionBody;
import software.amazon.awscdk.services.stepfunctions.IStateMachine;
import software.amazon.awscdk.services.stepfunctions.StateMachine;
import software.constructs.Construct;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IdpStack extends Stack {

    private final ITable idpTable;
    private final ILogGroup idpLogGroup;
    private final ILayerVersion idpCommonLayer;
    private final IFunction queueSenderFunction;
    private final IFunction queueProcessorFunction;
    private final IFunction ocrFunction;
    private final IFunction classificationFunction;
    private final IFunction extractionFunction;
    private final IFunction assessmentFunction;
    private final IFunction ruleValidationFunction;
    private final IFunction summarizationFunction;
    private final IFunction processResultsFunction;
    private final IFunction completionFunction;
    private final IStateMachine pipelineStateMachine;

    public IdpStack(final Construct scope, final IdpStackProps props) {
        super(scope, "AduacloudIdp-" + props.getEnvironmentName(), props.getStackProps());

        String env = props.getEnvironmentName();
        String region = getRegion();

        // ==========================================
        // DynamoDB Table
        // ==========================================
        idpTable = Table.Builder.create(this, "AduacloudIdpTable-" + env)
                .tableName("aduacloud-" + env + "-idp-table")
                .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .timeToLiveAttribute("ExpiresAfter")
                .removalPolicy(props.getRemovalPolicy())
                .build();

        // ==========================================
        // CloudWatch Log Group
        // ==========================================
        idpLogGroup = LogGroup.Builder.create(this, "AduacloudIdpLogGroup-" + env)
                .logGroupName("/aws/lambda/aduacloud-" + env + "/idp-pipeline")
                .retention(RetentionDays.ONE_MONTH)
                .removalPolicy(props.getRemovalPolicy())
                .build();

        // ==========================================
        // Common Lambda Layer
        // ==========================================
        String layerKey = LambdaVersionUtils.getLatestLambdaVersionCodeFileKey(
                props.getLambdaArtifactsBucketName(), "common-layer", region);
        idpCommonLayer = LayerVersion.Builder.create(this, "AduacloudIdpCommonLayer-" + env)
                .code(Code.fromBucket(props.getLambdaArtifactsBucket(), layerKey))
                .compatibleRuntimes(List.of(Runtime.PYTHON_3_12))
                .description("common shared library for serverless Lambdas")
                .build();

        // ==========================================
        // queue_sender
        // ==========================================
        IRole queueSenderRole = Role.Builder.create(this, "AduacloudIdpQueueSenderRole-" + env)
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .build();

        idpTable.grantWriteData(queueSenderRole);
        props.getInputBucket().grantRead(queueSenderRole);
        props.getDocumentQueue().grantSendMessages(queueSenderRole);
        idpLogGroup.grantWrite(queueSenderRole);

        queueSenderRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogStream", "logs:PutLogEvents"))
                .resources(List.of(idpLogGroup.getLogGroupArn() + ":*"))
                .build());

        String codeKey = LambdaVersionUtils.getLatestLambdaVersionCodeFileKey(
                props.getLambdaArtifactsBucketName(), "idp-queue_sender", region);
        queueSenderFunction = Function.Builder.create(this, "AduacloudIdpQueueSender-" + env)
                .functionName("aduacloud-" + env + "-idp-queue-sender")
                .runtime(Runtime.PYTHON_3_12)
                .handler("handler.handler")
                .code(Code.fromBucket(props.getLambdaArtifactsBucket(), codeKey))
                .memorySize(256)
                .timeout(Duration.seconds(30))
                .role(queueSenderRole)
                .layers(List.of(idpCommonLayer))
                .loggingFormat(LoggingFormat.JSON)
                .logGroup(idpLogGroup)
                .environment(Map.ofEntries(
                        Map.entry("LOG_LEVEL", "INFO"),
                        Map.entry("DEFAULT_CONFIG_VERSION", "default"),
                        Map.entry("QUEUE_URL", props.getDocumentQueue().getQueueUrl()),
                        Map.entry("OUTPUT_BUCKET", props.getOutputBucket().getBucketName()),
                        Map.entry("TRACKING_TABLE", idpTable.getTableName()),
                        Map.entry("DOCUMENT_TRACKING_MODE", "dynamodb"),
                        Map.entry("DATA_RETENTION_IN_DAYS", "7")
                ))
                .build();

        /*
        Se comenta para eviar referencia ciclica entre StorageStack <-> IdpStack
        Sucede debido a que el CDK cree que la function debe existir antes de la creacion del bucket, y viceversa.
        props.getInputBucket().addEventNotification(
                software.amazon.awscdk.services.s3.EventType.OBJECT_CREATED,
                new LambdaDestination(queueSenderFunction)
        );
         */

        CfnOutput.Builder.create(this, "QueueSenderFunctionArn")
                .value(queueSenderFunction.getFunctionArn())
                .description("Queue Sender Lambda ARN")
                .build();

        // ==========================================
        // Pipeline Lambda functions (7 total)
        // ==========================================
        // Bedrock model ARNs (cross-region inference profiles):
        // - Nova 2 Lite: classification (and any other enabled stage).
        // - Claude Haiku 4.5: extraction.
        List<String> bedrockModelArns = List.of(
                "arn:aws:bedrock:*::foundation-model/amazon.nova-2-lite-v1:0",
                String.format("arn:aws:bedrock:*:%s:inference-profile/us.amazon.nova-2-lite-v1:0", this.getAccount()),
                "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-4-5-20251001-v1:0",
                String.format("arn:aws:bedrock:*:%s:inference-profile/us.anthropic.claude-haiku-4-5-20251001-v1:0", this.getAccount())
        );

        // Lambda env vars shared by all pipeline functions
        Map<String, String> baseEnv = new HashMap<>();
        baseEnv.put("LOG_LEVEL", "INFO");
        baseEnv.put("TRACKING_TABLE", idpTable.getTableName());
        baseEnv.put("DOCUMENT_TRACKING_MODE", "dynamodb");
        baseEnv.put("WORKING_BUCKET", props.getWorkingBucket().getBucketName());
        baseEnv.put("OUTPUT_BUCKET", props.getOutputBucket().getBucketName());

        // --- ocr_function ---
        IRole ocrRole = createIdpLambdaRole(this, "AduacloudIdpOcrRole", env, props);
        props.getInputBucket().grantRead(ocrRole);
        props.getWorkingBucket().grantReadWrite(ocrRole);
        idpTable.grantReadWriteData(ocrRole);
        ocrRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("textract:DetectDocumentText", "textract:AnalyzeDocument"))
                .resources(List.of("*"))
                .build());

        ocrFunction = createPattern2Function(
                "AduacloudIdpOcrFunction", env, "idp-ocr-function",
                "idp-ocr", props, ocrRole, baseEnv, 512, Duration.seconds(300));

        // --- classification_function ---
        IRole classificationRole = createIdpLambdaRole(this, "AduacloudIdpClassificationRole", env, props);
        props.getWorkingBucket().grantReadWrite(classificationRole);
        idpTable.grantReadWriteData(classificationRole);
        classificationRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                .resources(bedrockModelArns)
                .build());

        classificationFunction = createPattern2Function(
                "AduacloudIdpClassificationFunction", env, "idp-classification-function",
                "idp-classification", props, classificationRole, baseEnv, 512, Duration.seconds(300));

        // --- extraction_function ---
        IRole extractionRole = createIdpLambdaRole(this, "AduacloudIdpExtractionRole", env, props);
        props.getWorkingBucket().grantReadWrite(extractionRole);
        props.getOutputBucket().grantReadWrite(extractionRole);
        idpTable.grantReadWriteData(extractionRole);
        extractionRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                .resources(bedrockModelArns)
                .build());

        extractionFunction = createPattern2Function(
                "AduacloudIdpExtractionFunction", env, "idp-extraction-function",
                "idp-extraction", props, extractionRole, baseEnv, 512, Duration.seconds(300));

        // --- assessment_function ---
        IRole assessmentRole = createIdpLambdaRole(this, "AduacloudIdpAssessmentRole", env, props);
        props.getWorkingBucket().grantReadWrite(assessmentRole);
        idpTable.grantReadWriteData(assessmentRole);
        assessmentRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                .resources(bedrockModelArns)
                .build());

        assessmentFunction = createPattern2Function(
                "AduacloudIdpAssessmentFunction", env, "idp-assessment-function",
                "idp-assessment", props, assessmentRole, baseEnv, 512, Duration.seconds(300));

        // --- process_results ---
        IRole processResultsRole = createIdpLambdaRole(this, "AduacloudIdpProcessResultsRole", env, props);
        props.getWorkingBucket().grantReadWrite(processResultsRole);
        props.getOutputBucket().grantReadWrite(processResultsRole);
        idpTable.grantReadWriteData(processResultsRole);

        processResultsFunction = createPattern2Function(
                "AduacloudIdpProcessResultsFunction", env, "idp-process-results",
                "idp-process_results", props, processResultsRole, baseEnv, 512, Duration.seconds(300));

        // --- rule_validation_function ---
        IRole ruleValidationRole = createIdpLambdaRole(this, "AduacloudIdpRuleValidationRole", env, props);
        props.getWorkingBucket().grantReadWrite(ruleValidationRole);
        props.getOutputBucket().grantReadWrite(ruleValidationRole);
        idpTable.grantReadWriteData(ruleValidationRole);
        ruleValidationRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                .resources(bedrockModelArns)
                .build());

        ruleValidationFunction = createPattern2Function(
                "AduacloudIdpRuleValidationFunction", env, "idp-rule-validation-function",
                "idp-rule_validation", props, ruleValidationRole, baseEnv, 512, Duration.seconds(300));

        // --- summarization_function ---
        IRole summarizationRole = createIdpLambdaRole(this, "AduacloudIdpSummarizationRole", env, props);
        props.getWorkingBucket().grantReadWrite(summarizationRole);
        props.getOutputBucket().grantReadWrite(summarizationRole);
        idpTable.grantReadWriteData(summarizationRole);
        summarizationRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"))
                .resources(bedrockModelArns)
                .build());

        summarizationFunction = createPattern2Function(
                "AduacloudIdpSummarizationFunction", env, "idp-summarization-function",
                "idp-summarization", props, summarizationRole, baseEnv, 512, Duration.seconds(900));

        // --- completion_function ---
        IRole completionRole = createIdpLambdaRole(this, "AduacloudIdpCompletionRole", env, props);
        props.getWorkingBucket().grantRead(completionRole);
        // Decrement the workflow concurrency counter on completion.
        idpTable.grantWriteData(completionRole);
        props.getNotificationQueue().grantSendMessages(completionRole);
        Map<String, String> completionEnv = new HashMap<>(baseEnv);
        completionEnv.put("NOTIFICATION_QUEUE_URL", props.getNotificationQueue().getQueueUrl());

        completionFunction = createPattern2Function(
                "AduacloudIdpCompletionFunction", env, "idp-completion-function",
                "idp-completion", props, completionRole, completionEnv, 256, Duration.seconds(30));

        CfnOutput.Builder.create(this, "CompletionFunctionArn")
                .value(completionFunction.getFunctionArn())
                .description("IDP Completion Lambda ARN")
                .build();

        // ==========================================
        // State Machine
        // ==========================================
        IRole stateMachineRole = Role.Builder.create(this, "AduacloudIdpStateMachineRole-" + env)
                .assumedBy(new ServicePrincipal("states.amazonaws.com"))
                .build();
        for (IFunction fn : List.of(
                ocrFunction, classificationFunction, extractionFunction,
                assessmentFunction, processResultsFunction, ruleValidationFunction, summarizationFunction,
                completionFunction)) {
            stateMachineRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                    .effect(Effect.ALLOW)
                    .actions(List.of("lambda:InvokeFunction"))
                    .resources(List.of(fn.getFunctionArn(), fn.getFunctionArn() + ":*"))
                    .build());
        }
        stateMachineRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogDelivery", "logs:CreateLogGroup",
                        "logs:CreateLogStream", "logs:DeleteLogDelivery",
                        "logs:DeleteLogGroup", "logs:DeleteLogStream",
                        "logs:DescribeLogGroups", "logs:DescribeLogStreams",
                        "logs:GetLogDelivery", "logs:GetLogEvents",
                        "logs:ListLogGroups", "logs:ListLogStreams",
                        "logs:PutLogEvents", "logs:PutLogEvents",
                        "logs:UpdateLogDelivery"))
                .resources(List.of("arn:aws:logs:*:*:*"))
                .build());

        // Load ASL definition and substitute Lambda ARNs
        String aslPath = System.getenv().getOrDefault("IDP_ASL_PATH", "../serverless/statemachine/idp-pipeline.asl.json");
        File aslFile = new File(aslPath);
        if (!aslFile.exists()) {
            aslFile = new File("serverless/statemachine/idp-pipeline.asl.json");
        }
        String aslJson;
        try {
            aslJson = java.nio.file.Files.readString(aslFile.toPath());
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read ASL file at " + aslFile.getAbsolutePath(), e);
        }

        Map<String, String> arnReplacements = Map.ofEntries(
                Map.entry("${OCRFunctionArn}", ocrFunction.getFunctionArn()),
                Map.entry("${ClassificationFunctionArn}", classificationFunction.getFunctionArn()),
                Map.entry("${ExtractionFunctionArn}", extractionFunction.getFunctionArn()),
                Map.entry("${AssessmentFunctionArn}", assessmentFunction.getFunctionArn()),
                Map.entry("${ProcessResultsLambdaArn}", processResultsFunction.getFunctionArn()),
                Map.entry("${RuleValidationLambdaArn}", ruleValidationFunction.getFunctionArn()),
                Map.entry("${SummarizationLambdaArn}", summarizationFunction.getFunctionArn()),
                Map.entry("${CompletionFunctionArn}", completionFunction.getFunctionArn())
        );
        for (Map.Entry<String, String> entry : arnReplacements.entrySet()) {
            aslJson = aslJson.replace(entry.getKey(), entry.getValue());
        }

        pipelineStateMachine = StateMachine.Builder.create(this, "AduacloudIdpPipelineSM-" + env)
                .stateMachineName("aduacloud-" + env + "-idp-pipeline")
                .definitionBody(DefinitionBody.fromString(aslJson))
                .role(stateMachineRole)
                .build();

        CfnOutput.Builder.create(this, "IdpPipelineStateMachineArn")
                .value(pipelineStateMachine.getStateMachineArn())
                .description("ADUACloud IDP Pipeline State Machine ARN")
                .build();

        // ==========================================
        // queue_processor
        // ==========================================
        IRole queueProcessorRole = Role.Builder.create(this, "AduacloudIdpQueueProcessorRole-" + env)
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .build();

        idpTable.grantReadWriteData(queueProcessorRole);
        pipelineStateMachine.grantStartExecution(queueProcessorRole);
        props.getDocumentQueue().grantConsumeMessages(queueProcessorRole);
        idpLogGroup.grantWrite(queueProcessorRole);

        queueProcessorRole.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogStream", "logs:PutLogEvents"))
                .resources(List.of(idpLogGroup.getLogGroupArn() + ":*"))
                .build());

        String queueProcessorCodeKey = LambdaVersionUtils.getLatestLambdaVersionCodeFileKey(
                props.getLambdaArtifactsBucketName(), "idp-queue_processor", region);
        queueProcessorFunction = Function.Builder.create(this, "AduacloudIdpQueueProcessor-" + env)
                .functionName("aduacloud-" + env + "-idp-queue-processor")
                .runtime(Runtime.PYTHON_3_12)
                .handler("handler.handler")
                .code(Code.fromBucket(props.getLambdaArtifactsBucket(), queueProcessorCodeKey))
                .memorySize(256)
                .timeout(Duration.seconds(60))
                .role(queueProcessorRole)
                .layers(List.of(idpCommonLayer))
                .loggingFormat(LoggingFormat.JSON)
                .logGroup(idpLogGroup)
                .environment(Map.ofEntries(
                        Map.entry("LOG_LEVEL", "INFO"),
                        Map.entry("TRACKING_TABLE", idpTable.getTableName()),
                        Map.entry("STATE_MACHINE_ARN", pipelineStateMachine.getStateMachineArn()),
                        Map.entry("MAX_CONCURRENT", "100"),
                        Map.entry("DATA_RETENTION_IN_DAYS", "7"),
                        Map.entry("DOCUMENT_TRACKING_MODE", "dynamodb")
                ))
                .build();

        queueProcessorFunction.addEventSource(SqsEventSource.Builder.create(props.getDocumentQueue())
                .batchSize(1)
                .maxBatchingWindow(Duration.seconds(1))
                .reportBatchItemFailures(true)
                .build());

        CfnOutput.Builder.create(this, "QueueProcessorFunctionArn")
                .value(queueProcessorFunction.getFunctionArn())
                .description("Queue Processor Lambda ARN")
                .build();

        // ==========================================
        // Shared outputs
        // ==========================================
        CfnOutput.Builder.create(this, "IdpTableName")
                .value(idpTable.getTableName())
                .description("IDP DynamoDB table name")
                .build();

        CfnOutput.Builder.create(this, "IdpLogGroupName")
                .value(idpLogGroup.getLogGroupName())
                .description("IDP shared CloudWatch Log Group name")
                .build();

        CfnOutput.Builder.create(this, "IdpCommonLayerArn")
                .value(idpCommonLayer.getLayerVersionArn())
                .description("IDP Common Lambda Layer ARN")
                .build();
    }

    private IRole createIdpLambdaRole(Construct scope, String idPrefix, String env, IdpStackProps props) {
        IRole role = Role.Builder.create(this, idPrefix + "-" + env)
                .assumedBy(new ServicePrincipal("lambda.amazonaws.com"))
                .managedPolicies(List.of(
                        ManagedPolicy.fromAwsManagedPolicyName("service-role/AWSLambdaBasicExecutionRole")
                ))
                .build();
        idpLogGroup.grantWrite(role);
        role.addToPrincipalPolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("logs:CreateLogStream", "logs:PutLogEvents"))
                .resources(List.of(idpLogGroup.getLogGroupArn() + ":*"))
                .build());
        return role;
    }

    private IFunction createPattern2Function(String constructId, String env, String functionName,
            String codeName, IdpStackProps props, IRole role,
            Map<String, String> baseEnv, int memoryMb, Duration timeout) {
        String codeKey = LambdaVersionUtils.getLatestLambdaVersionCodeFileKey(
                props.getLambdaArtifactsBucketName(), codeName, getRegion());
        return Function.Builder.create(this, constructId + "-" + env)
                .functionName("aduacloud-" + env + "-" + functionName)
                .runtime(Runtime.PYTHON_3_12)
                .handler("handler.handler")
                .code(Code.fromBucket(props.getLambdaArtifactsBucket(), codeKey))
                .memorySize(memoryMb)
                .timeout(timeout)
                .role(role)
                .layers(List.of(idpCommonLayer))
                .loggingFormat(LoggingFormat.JSON)
                .logGroup(idpLogGroup)
                .environment(baseEnv)
                .build();
    }

    public ITable getIdpTable() { return idpTable; }
    public ILogGroup getIdpLogGroup() { return idpLogGroup; }
    public ILayerVersion getIdpCommonLayer() { return idpCommonLayer; }
    public IFunction getQueueSenderFunction() { return queueSenderFunction; }
    public IFunction getQueueProcessorFunction() { return queueProcessorFunction; }
    public IFunction getOcrFunction() { return ocrFunction; }
    public IFunction getClassificationFunction() { return classificationFunction; }
    public IFunction getExtractionFunction() { return extractionFunction; }
    public IFunction getAssessmentFunction() { return assessmentFunction; }
    public IFunction getRuleValidationFunction() { return ruleValidationFunction; }
    public IFunction getSummarizationFunction() { return summarizationFunction; }
    public IFunction getProcessResultsFunction() { return processResultsFunction; }
    public IFunction getCompletionFunction() { return completionFunction; }
    public IStateMachine getPipelineStateMachine() { return pipelineStateMachine; }
}
