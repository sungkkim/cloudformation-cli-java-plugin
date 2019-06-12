package com.amazonaws.cloudformation;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.cloudformation.exceptions.ResourceAlreadyExistsException;
import com.amazonaws.cloudformation.exceptions.ResourceNotFoundException;
import com.amazonaws.cloudformation.proxy.HandlerErrorCode;
import com.amazonaws.cloudformation.proxy.HandlerResponse;
import com.amazonaws.cloudformation.resource.Serializer;
import com.amazonaws.cloudformation.resource.Validator;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.cloudformation.exceptions.TerminalException;
import com.amazonaws.cloudformation.injection.CredentialsProvider;
import com.amazonaws.cloudformation.metrics.MetricsPublisher;
import com.amazonaws.cloudformation.proxy.CallbackAdapter;
import com.amazonaws.cloudformation.proxy.Credentials;
import com.amazonaws.cloudformation.proxy.HandlerRequest;
import com.amazonaws.cloudformation.proxy.OperationStatus;
import com.amazonaws.cloudformation.proxy.ProgressEvent;
import com.amazonaws.cloudformation.proxy.ResourceHandlerRequest;
import com.amazonaws.cloudformation.resource.SchemaValidator;
import com.amazonaws.cloudformation.resource.exceptions.ValidationException;
import com.amazonaws.cloudformation.scheduler.CloudWatchScheduler;
import com.fasterxml.jackson.core.type.TypeReference;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.ArgumentMatchers;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class LambdaWrapperTest {

    private static final String TEST_DATA_BASE_PATH = "src/test/java/com/amazonaws/cloudformation/data/%s";

    @Mock
    private CallbackAdapter<TestModel> callbackAdapter;

    @Mock
    private CredentialsProvider credentialsProvider;

    @Mock
    private MetricsPublisher metricsPublisher;

    @Mock
    private CloudWatchScheduler scheduler;

    @Mock
    private SchemaValidator validator;

    @Mock
    private ResourceHandlerRequest<TestModel> resourceHandlerRequest;

    public static InputStream loadRequestStream(final String fileName) {
        final File file = new File(String.format(TEST_DATA_BASE_PATH, fileName));

        try {
            return new FileInputStream(file);
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Context getLambdaContext() {
        final LambdaLogger lambdaLogger = mock(LambdaLogger.class);

        final Context context = mock(Context.class);
        lenient().when(context.getInvokedFunctionArn()).thenReturn("arn:aws:lambda:aws-region:acct-id:function:testHandler:PROD");
        when(context.getLogger()).thenReturn(lambdaLogger);

        return context;
    }

    private void verifyInitialiseRuntime() {
        verify(credentialsProvider).setCredentials(any(Credentials.class));
        verify(callbackAdapter).refreshClient();
        verify(metricsPublisher).refreshClient();
        verify(scheduler).refreshClient();
    }

    private void verifyHandlerResponse(final OutputStream out,
                                       final HandlerResponse<TestModel> expected) throws IOException {
        final Serializer serializer = new Serializer();
        final HandlerResponse<TestModel> handlerResponse =
            serializer.deserialize(out.toString(), new TypeReference<HandlerResponse<TestModel>>(){});

        assertThat(handlerResponse.getBearerToken()).isEqualTo(expected.getBearerToken());
        assertThat(handlerResponse.getErrorCode()).isEqualTo(expected.getErrorCode());
        assertThat(handlerResponse.getMessage()).isEqualTo(expected.getMessage());
        assertThat(handlerResponse.getNextToken()).isEqualTo(expected.getNextToken());
        assertThat(handlerResponse.getOperationStatus()).isEqualTo(expected.getOperationStatus());
        assertThat(handlerResponse.getStabilizationData()).isEqualTo(expected.getStabilizationData());
        assertThat(handlerResponse.getResourceModel()).isEqualTo(expected.getResourceModel());
    }

    private void invokeHandler_nullResponse_returnsFailure(final String requestDataPath,
                                                final Action action) throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // a null response is a terminal fault
        wrapper.setInvokeHandlerResponse(null);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // validation failure metric should be published for final error handling
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), any(), any(TerminalException.class));

            // all metrics should be published even on terminal failure
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(action));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(action), anyLong());

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(
                    any(JSONObject.class), any(InputStream.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED)
                .message("Handler failed to provide a response.")
                .build()
            );
        }
    }

    @Test
    public void invokeHandlerForCreate_nullResponse_returnsFailure() throws IOException {
        invokeHandler_nullResponse_returnsFailure("create.request.json", Action.CREATE);
    }

    @Test
    public void invokeHandlerForRead_nullResponse_returnsFailure() throws IOException {
        invokeHandler_nullResponse_returnsFailure("read.request.json", Action.READ);
    }

    @Test
    public void invokeHandlerForUpdate_nullResponse_returnsFailure() throws IOException {
        invokeHandler_nullResponse_returnsFailure("update.request.json", Action.UPDATE);
    }

    @Test
    public void invokeHandlerForDelete_nullResponse_returnsFailure() throws IOException {
        invokeHandler_nullResponse_returnsFailure("delete.request.json", Action.DELETE);
    }

    @Test
    public void invokeHandlerForList_nullResponse_returnsFailure() throws IOException {
        invokeHandler_nullResponse_returnsFailure("list.request.json", Action.LIST);
    }

    private void invokeHandler_handlerFailed_returnsFailure(final String requestDataPath,
                                          final Action action) throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // explicit fault response is treated as an unsuccessful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.FAILED)
            .errorCode(HandlerErrorCode.InternalFailure)
            .message("Custom Fault")
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(action));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(
                    any(JSONObject.class), any(InputStream.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED)
                .message("Custom Fault")
                .build()
            );
        }
    }

    @Test
    public void invokeHandlerForCreate_handlerFailed_returnsFailure() throws IOException {
        invokeHandler_handlerFailed_returnsFailure("create.request.json", Action.CREATE);
    }

    @Test
    public void invokeHandlerForRead_handlerFailed_returnsFailure() throws IOException {
        invokeHandler_handlerFailed_returnsFailure("read.request.json", Action.READ);
    }

    @Test
    public void invokeHandlerForUpdeate_handlerFailed_returnsFailure() throws IOException {
        invokeHandler_handlerFailed_returnsFailure("update.request.json", Action.UPDATE);
    }

    @Test
    public void invokeHandlerForDelete_handlerFailed_returnsFailure() throws IOException {
        invokeHandler_handlerFailed_returnsFailure("delete.request.json", Action.DELETE);
    }

    @Test
    public void invokeHandlerForList_handlerFailed_returnsFailure() throws IOException {
        invokeHandler_handlerFailed_returnsFailure("list.request.json", Action.LIST);
    }

    private void invokeHandler_CompleteSynchronously_returnsSuccess(final String requestDataPath,
                                                         final Action action) throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // if the handler responds Complete, this is treated as a successful synchronous completion
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS)
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(action));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(
                    any(JSONObject.class), any(InputStream.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.SUCCESS)
                .build()
            );
        }
    }

    @Test
    public void invokeHandlerForCreate_CompleteSynchronously_returnsSuccess() throws IOException {
        invokeHandler_CompleteSynchronously_returnsSuccess("create.request.json", Action.CREATE);
    }

    @Test
    public void invokeHandlerForRead_CompleteSynchronously_returnsSuccess() throws IOException {
        invokeHandler_CompleteSynchronously_returnsSuccess("read.request.json", Action.READ);
    }

    @Test
    public void invokeHandlerForUpdate_CompleteSynchronously_returnsSuccess() throws IOException {
        invokeHandler_CompleteSynchronously_returnsSuccess("update.request.json", Action.UPDATE);
    }

    @Test
    public void invokeHandlerForDelete_CompleteSynchronously_returnsSuccess() throws IOException {
        invokeHandler_CompleteSynchronously_returnsSuccess("delete.request.json", Action.DELETE);
    }

    @Test
    public void invokeHandlerForList_CompleteSynchronously_returnsSuccess() throws IOException {
        invokeHandler_CompleteSynchronously_returnsSuccess("list.request.json", Action.LIST);
    }

    private void invokeHandler_InProgress_returnsInProgress(final String requestDataPath,
                                              final Action action) throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS)
            .resourceModel(model)
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        lenient().when(resourceHandlerRequest.getDesiredResourceState()).thenReturn(model);
        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(action));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(
                    any(JSONObject.class), any(InputStream.class));
            }

            // re-invocation via CloudWatch should occur
            verify(scheduler, times(1)).rescheduleAfterMinutes(
                anyString(), eq(0), ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());

            // this was a first invocation, so no cleanup is required
            verifyNoMoreInteractions(scheduler);

            // CloudFormation should receive a callback invocation
            verify(callbackAdapter, times(1)).reportProgress(
                eq("123456"),
                isNull(),
                eq(OperationStatus.IN_PROGRESS),
                eq(TestModel.builder().property1("abc").property2(123).build()),
                isNull()
            );

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.IN_PROGRESS)
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandlerForCreate_InProgress_returnsInProgress() throws IOException {
        invokeHandler_InProgress_returnsInProgress("create.request.json", Action.CREATE);
    }

    @Test
    public void invokeHandlerForRead_InProgress_returnsInProgress() throws IOException {
        invokeHandler_InProgress_returnsInProgress("read.request.json", Action.READ);
    }

    @Test
    public void invokeHandlerForUpdate_InProgress_returnsInProgress() throws IOException {
        invokeHandler_InProgress_returnsInProgress("update.request.json", Action.UPDATE);
    }

    @Test
    public void invokeHandlerForDelete_InProgress_returnsInProgress() throws IOException {
        invokeHandler_InProgress_returnsInProgress("delete.request.json", Action.DELETE);
    }

    @Test
    public void invokeHandlerForList_InProgress_returnsInProgress() throws IOException {
        invokeHandler_InProgress_returnsInProgress("list.request.json", Action.LIST);
    }

    private void reInvokeHandler_InProgress_returnsInProgress(final String requestDataPath,
                                            final Action action) throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used, and any interval >= 1 minute is scheduled
        // against CloudWatch. Shorter intervals are able to run locally within same function context if runtime permits
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS)
            .callbackDelaySeconds(60)
            .resourceModel(model)
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(action));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(action), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(
                    any(JSONObject.class), any(InputStream.class));
            }

            // re-invocation via CloudWatch should occur
            verify(scheduler, times(1)).rescheduleAfterMinutes(
                anyString(), eq(1), ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());

            // this was a re-invocation, so a cleanup is required
            verify(scheduler, times(1)).cleanupCloudWatchEvents(
                eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
                eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be")
            );

            // CloudFormation should receive a callback invocation
            verify(callbackAdapter, times(1)).reportProgress(
                eq("123456"),
                isNull(),
                eq(OperationStatus.IN_PROGRESS),
                eq(TestModel.builder().property1("abc").property2(123).build()),
                isNull()
            );

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.IN_PROGRESS)
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void reInvokeHandlerForCreate_InProgress_returnsInProgress() throws IOException {
        reInvokeHandler_InProgress_returnsInProgress("create.with-request-context.request.json", Action.CREATE);
    }

    @Test
    public void reInvokeHandlerForRead_InProgress_returnsInProgress() throws IOException {
        // TODO: READ handlers must return synchronously so this is probably a fault
        //reInvokeHandler_InProgress_returnsInProgress("read.with-request-context.request.json", Action.READ);
    }

    @Test
    public void reInvokeHandlerForUpdate_InProgress_returnsInProgress() throws IOException {
        reInvokeHandler_InProgress_returnsInProgress("update.with-request-context.request.json", Action.UPDATE);
    }

    @Test
    public void reInvokeHandlerForDelete_InProgress_returnsInProgress() throws IOException {
        reInvokeHandler_InProgress_returnsInProgress("delete.with-request-context.request.json", Action.DELETE);
    }

    @Test
    public void reInvokeHandlerForList_InProgress_returnsInProgress() throws IOException {
        // TODO: LIST handlers must return synchronously so this is probably a fault
        //reInvokeHandler_InProgress_returnsInProgress("list.with-request-context.request.json", Action.LIST);
    }

    private void invokeHandler_SchemaValidationFailure(final String requestDataPath,
                                                           final Action action) throws IOException {
        doThrow(ValidationException.class)
            .when(validator).validateObject(any(JSONObject.class), any(InputStream.class));
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream(requestDataPath); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // validation failure metric should be published but no others
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), eq(action), any(Exception.class));

            // all metrics should be published, even for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(action));

            // duration metric only published when the provider handler is invoked
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            if (action == Action.CREATE || action == Action.UPDATE || action == Action.DELETE) {
                verify(validator, times(1)).validateObject(
                    any(JSONObject.class), any(InputStream.class));
            }

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // CloudFormation should NOT receive a callback invocation
            verifyNoMoreInteractions(callbackAdapter);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("InvalidRequest")
                .operationStatus(OperationStatus.FAILED)
                .message("Model validation failed")
                .build()
            );
        }
    }

    @Test
    public void invokeHandlerForSchema_SchemaValidationFailure() throws IOException {
        invokeHandler_SchemaValidationFailure("create.request.json", Action.CREATE);
    }

    @Test
    public void invokeHandlerForRead_SchemaValidationFailure() throws IOException {
        // TODO: READ handlers must return synchronously so this is probably a fault
        //invokeHandler_SchemaValidationFailure("read.with-request-context.request.json", Action.READ);
    }

    @Test
    public void invokeHandlerForUpdate_SchemaValidationFailure() throws IOException {
        invokeHandler_SchemaValidationFailure("update.request.json", Action.UPDATE);
    }

    @Test
    public void invokeHandlerForDelete_SchemaValidationFailure() throws IOException {
        invokeHandler_SchemaValidationFailure("delete.request.json", Action.DELETE);
    }

    @Test
    public void invokeHandlerForList_SchemaValidationFailure() throws IOException {
        // TODO: LIST handlers must return synchronously so this is probably a fault
        //invokeHandler_SchemaValidationFailure("list.with-request-context.request.json", Action.LIST);
    }

    @Test
    public void invokeHandler_extraneousModelFields_causesSchemaValidationFailure() throws IOException {
        // use actual validator to verify behaviour
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, new Validator() { });

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.with-extraneous-model-fields.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // validation failure metric should be published but no others
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), eq(Action.CREATE), any(Exception.class));

            // all metrics should be published, even for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(Action.CREATE));

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // CloudFormation should NOT receive a callback invocation
            verifyNoMoreInteractions(callbackAdapter);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("InvalidRequest")
                .operationStatus(OperationStatus.FAILED)
                .message("Model validation failed (#: extraneous key [fieldCausesValidationError] is not permitted)")
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_withMalformedRequest_causesSchemaValidationFailure() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS)
            .resourceModel(model)
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        wrapper.setTransformResponse(resourceHandlerRequest);

        // our ObjectMapper implementation will ignore extraneous fields rather than fail them
        // this slightly loosens the coupling between caller (CloudFormation) and handlers.
        try (final InputStream in = loadRequestStream("malformed.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.SUCCESS)
                .resourceModel(TestModel.builder().build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_withoutPlatformCredentials_returnsFailure() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("create.request-without-platform-credentials.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED)
                .message("Missing required platform credentials")
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_withDefaultInjection_returnsSuccess() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        wrapper.setTransformResponse(resourceHandlerRequest);

        // respond with immediate success to avoid callback invocation
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS)
            .resourceModel(model)
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        // without platform credentials the handler is unable to do
        // basic SDK initialization and any such request should fail fast
        try (final InputStream in = loadRequestStream("create.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.SUCCESS)
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_failToRescheduleInvocation() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();
        model.setProperty1("abc");
        model.setProperty2(123);
        doThrow(new AmazonServiceException("some error")).when(scheduler).rescheduleAfterMinutes(anyString(),
            anyInt(), ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());
        wrapper.setTransformResponse(resourceHandlerRequest);

        // respond with in progress status to trigger callback invocation
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS)
            .resourceModel(model)
            .build();
        wrapper.setInvokeHandlerResponse(pe);

        try (final InputStream in = loadRequestStream("create.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("ServiceException")
                .operationStatus(OperationStatus.FAILED)
                .message("some error (Service: null; Status Code: 0; Error Code: null; Request ID: null)")
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_clientsRefreshedOnEveryInvoke() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);

        Context context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        verify(callbackAdapter, times(1)).refreshClient();
        verify(metricsPublisher, times(1)).refreshClient();
        verify(scheduler, times(1)).refreshClient();

        // invoke the same wrapper instance again to ensure client is refreshed
        context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        verify(callbackAdapter, times(2)).refreshClient();
        verify(metricsPublisher, times(2)).refreshClient();
        verify(scheduler, times(2)).refreshClient();
    }

    @Test
    public void invokeHandler_platformCredentialsRefreshedOnEveryInvoke() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);

        Context context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        final Credentials expected = new Credentials(
            "32IEHAHFIAG538KYASAI",
            "0O2hop/5vllVHjbA8u52hK8rLcroZpnL5NPGOi66",
            "gqe6eIsFPHOlfhc3RKl5s5Y6Dy9PYvN1CEYsswz5TQUsE8WfHD6LPK549euXm4Vn4INBY9nMJ1cJe2mxTYFdhWHSnkOQv2SHemal"
        );
        verify(credentialsProvider, times(1)).setCredentials(eq(expected));

        // invoke the same wrapper instance again to ensure client is refreshed
        context = getLambdaContext();
        try (InputStream in = loadRequestStream("create.request.with-new-credentials.json"); OutputStream out = new ByteArrayOutputStream()) {
            wrapper.handleRequest(in, out, context);
        }

        final Credentials expectedNew = new Credentials(
            "GT530IJDHALYZQSZZ8XG",
            "UeJEwC/dqcYEn2viFd5TjKjR5TaMOfdeHrlLXxQL",
            "469gs8raWJCaZcItXhGJ7dt3urI13fOTcde6ibhuHJz6r6bRRCWvLYGvCsqrN8WUClYL9lxZHymrWXvZ9xN0GoI2LFdcAAinZk5t"
        );

        verify(credentialsProvider, times(1)).setCredentials(eq(expectedNew));
    }

    @Test
    public void invokeHandler_withNoResponseEndpoint_returnsFailure() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS)
            .resourceModel(model)
            .build();
        wrapper.setInvokeHandlerResponse(pe);
        wrapper.setTransformResponse(resourceHandlerRequest);

        // our ObjectMapper implementation will ignore extraneous fields rather than fail them
        // this slightly loosens the coupling between caller (CloudFormation) and handlers.
        try (final InputStream in = loadRequestStream("no-response-endpoint.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // malformed input exception is published
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), any(), any(TerminalException.class));

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("InternalFailure")
                .operationStatus(OperationStatus.FAILED)
                .message("No callback endpoint received")
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_localReinvokeWithSufficientRemainingTime() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe1 = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS) // iterate locally once
            .callbackDelaySeconds(5)
            .resourceModel(model)
            .build();
        final ProgressEvent<TestModel, TestContext> pe2 = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.SUCCESS) // then exit loop
            .resourceModel(model)
            .build();
        wrapper.enqueueResponses(Arrays.asList(pe1, pe2));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.with-request-context.request.json");
             final OutputStream out = new ByteArrayOutputStream()) {

            final Context context = getLambdaContext();
            when(context.getRemainingTimeInMillis()).thenReturn(60000); // ~1 minute

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(Action.CREATE));
            verify(metricsPublisher, times(2)).publishDurationMetric(
                any(Instant.class), eq(Action.CREATE), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));

            // this was a re-invocation, so a cleanup is required
            verify(scheduler, times(1)).cleanupCloudWatchEvents(
                eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
                eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be")
            );

            // re-invocation via CloudWatch should NOT occur for <60 when Lambda remaining time allows
            verifyNoMoreInteractions(scheduler);

            final ArgumentCaptor<String> bearerTokenCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<HandlerErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(HandlerErrorCode.class);
            final ArgumentCaptor<OperationStatus> operationStatusCaptor = ArgumentCaptor.forClass(OperationStatus.class);
            final ArgumentCaptor<TestModel> resourceModelCaptor = ArgumentCaptor.forClass(TestModel.class);
            final ArgumentCaptor<String> statusMessageCaptor = ArgumentCaptor.forClass(String.class);

            verify(callbackAdapter, times(2)).reportProgress(
                bearerTokenCaptor.capture(),
                errorCodeCaptor.capture(),
                operationStatusCaptor.capture(),
                resourceModelCaptor.capture(),
                statusMessageCaptor.capture()
            );

            final List<String> bearerTokens = bearerTokenCaptor.getAllValues();
            final List<HandlerErrorCode> errorCodes = errorCodeCaptor.getAllValues();
            final List<OperationStatus> operationStatuses = operationStatusCaptor.getAllValues();
            final List<TestModel> resourceModels = resourceModelCaptor.getAllValues();
            final List<String> statusMessages = statusMessageCaptor.getAllValues();

            // CloudFormation should receive 2 callback invocation; once for IN_PROGRESS, once for COMPLETION
            assertThat(bearerTokens).containsExactly("123456", "123456");
            assertThat(errorCodes).containsExactly(null, null);
            assertThat(resourceModels).containsExactly(
                TestModel.builder().property1("abc").property2(123).build(),
                TestModel.builder().property1("abc").property2(123).build());
            assertThat(statusMessages).containsExactly(null, null);
            assertThat(operationStatuses).containsExactly(OperationStatus.IN_PROGRESS, OperationStatus.SUCCESS);

            // verify final output response is for success response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.SUCCESS)
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_localReinvokeWithSufficientRemainingTimeForFirstIterationOnly_SchedulesViaCloudWatch() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = TestModel.builder().property1("abc").property2(123).build();

        // an InProgress response is always re-scheduled.
        // If no explicit time is supplied, a 1-minute interval is used
        final ProgressEvent<TestModel, TestContext> pe1 = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS) // iterate locally once
            .callbackDelaySeconds(5)
            .resourceModel(model)
            .build();
        final ProgressEvent<TestModel, TestContext> pe2 = ProgressEvent.<TestModel, TestContext>builder()
            .status(OperationStatus.IN_PROGRESS) // second iteration will exceed runtime
            .callbackDelaySeconds(5)
            .resourceModel(model)
            .build();
        wrapper.enqueueResponses(Arrays.asList(pe1, pe2));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.with-request-context.request.json");
             final OutputStream out = new ByteArrayOutputStream()) {

            final Context context = getLambdaContext();
            when(context.getRemainingTimeInMillis())
                .thenReturn(
                    60000, // 60 seconds
                    6000); // 6 seconds is <= 1.2 * 5 seconds requested, causes CWE reinvoke

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(Action.CREATE));
            verify(metricsPublisher, times(2)).publishDurationMetric(
                any(Instant.class), eq(Action.CREATE), anyLong());

            // validation failure metric should not be published
            verifyNoMoreInteractions(metricsPublisher);

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));

            // re-invocation via CloudWatch should occur for the second iteration
            verify(scheduler, times(1)).rescheduleAfterMinutes(
                anyString(), eq(0), ArgumentMatchers.<HandlerRequest<TestModel, TestContext>>any());

            // this was a re-invocation, so a cleanup is required
            verify(scheduler, times(1)).cleanupCloudWatchEvents(
                eq("reinvoke-handler-4754ac8a-623b-45fe-84bc-f5394118a8be"),
                eq("reinvoke-target-4754ac8a-623b-45fe-84bc-f5394118a8be")
            );

            final ArgumentCaptor<String> bearerTokenCaptor = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<HandlerErrorCode> errorCodeCaptor = ArgumentCaptor.forClass(HandlerErrorCode.class);
            final ArgumentCaptor<OperationStatus> operationStatusCaptor = ArgumentCaptor.forClass(OperationStatus.class);
            final ArgumentCaptor<TestModel> resourceModelCaptor = ArgumentCaptor.forClass(TestModel.class);
            final ArgumentCaptor<String> statusMessageCaptor = ArgumentCaptor.forClass(String.class);

            verify(callbackAdapter, times(2)).reportProgress(
                bearerTokenCaptor.capture(),
                errorCodeCaptor.capture(),
                operationStatusCaptor.capture(),
                resourceModelCaptor.capture(),
                statusMessageCaptor.capture()
            );

            final List<String> bearerTokens = bearerTokenCaptor.getAllValues();
            final List<HandlerErrorCode> errorCodes = errorCodeCaptor.getAllValues();
            final List<OperationStatus> operationStatuses = operationStatusCaptor.getAllValues();
            final List<TestModel> resourceModels = resourceModelCaptor.getAllValues();
            final List<String> statusMessages = statusMessageCaptor.getAllValues();

            // CloudFormation should receive 2 callback invocation; both for IN_PROGRESS
            assertThat(bearerTokens).containsExactly("123456", "123456");
            assertThat(errorCodes).containsExactly(null, null);
            assertThat(resourceModels).containsExactly(
                TestModel.builder().property1("abc").property2(123).build(),
                TestModel.builder().property1("abc").property2(123).build());
            assertThat(statusMessages).containsExactly(null, null);
            assertThat(operationStatuses).containsExactly(OperationStatus.IN_PROGRESS, OperationStatus.IN_PROGRESS);

            // verify final output response is for second IN_PROGRESS response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .operationStatus(OperationStatus.IN_PROGRESS)
                .resourceModel(TestModel.builder().property1("abc").property2(123).build())
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_throwsAmazonServiceException_returnsServiceException() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new AmazonServiceException("some error"));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(Action.CREATE));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(Action.CREATE), anyLong());

            // failure metric should be published
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), any(), any(AmazonServiceException.class));

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("ServiceException")
                .operationStatus(OperationStatus.FAILED)
                .message("some error (Service: null; Status Code: 0; Error Code: null; Request ID: null)")
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_throwsResourceAlreadyExistsException_returnsAlreadyExists() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new ResourceAlreadyExistsException("AWS::Test::TestModel", "id-1234"));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(Action.CREATE));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(Action.CREATE), anyLong());

            // failure metric should be published
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), any(), any(ResourceAlreadyExistsException.class));

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("AlreadyExists")
                .operationStatus(OperationStatus.FAILED)
                .message("Resource of type 'AWS::Test::TestModel' with identifier 'id-1234' already exists.")
                .build()
            );
        }
    }

    @Test
    public void invokeHandler_throwsResourceNotFoundException_returnsNotFound() throws IOException {
        final WrapperOverride wrapper = new WrapperOverride(callbackAdapter, credentialsProvider, metricsPublisher, scheduler, validator);
        final TestModel model = new TestModel();

        // exceptions are caught consistently by LambdaWrapper
        wrapper.setInvokeHandlerException(new ResourceNotFoundException("AWS::Test::TestModel", "id-1234"));

        wrapper.setTransformResponse(resourceHandlerRequest);

        try (final InputStream in = loadRequestStream("create.request.json"); final OutputStream out = new ByteArrayOutputStream()) {
            final Context context = getLambdaContext();

            wrapper.handleRequest(in, out, context);

            // verify initialiseRuntime was called and initialised dependencies
            verifyInitialiseRuntime();

            // all metrics should be published, once for a single invocation
            verify(metricsPublisher, times(1)).setResourceTypeName(
                "AWS::Test::TestModel");
            verify(metricsPublisher, times(1)).publishInvocationMetric(
                any(Instant.class), eq(Action.CREATE));
            verify(metricsPublisher, times(1)).publishDurationMetric(
                any(Instant.class), eq(Action.CREATE), anyLong());

            // failure metric should be published
            verify(metricsPublisher, times(1)).publishExceptionMetric(
                any(Instant.class), any(), any(ResourceNotFoundException.class));

            // verify that model validation occurred for CREATE/UPDATE/DELETE
            verify(validator, times(1)).validateObject(
                any(JSONObject.class), any(InputStream.class));

            // no re-invocation via CloudWatch should occur
            verifyNoMoreInteractions(scheduler);

            // verify output response
            verifyHandlerResponse(out, HandlerResponse.<TestModel>builder()
                .bearerToken("123456")
                .errorCode("NotFound")
                .operationStatus(OperationStatus.FAILED)
                .message("Resource of type 'AWS::Test::TestModel' with identifier 'id-1234' was not found.")
                .build()
            );
        }
    }
}