package com.chatchat.common.bridge;

import com.chatchat.common.kernel.KernelViolationException;

/**
 * Template implementation enforcing version, operation and data-boundary checks before adapters run.
 */
public abstract class AbstractRuntimeBridge<I, O> implements RuntimeBridge<I, O> {

    @Override
    public final BridgeResponse<O> exchange(BridgeRequest<I> request) {
        if (request == null) throw new IllegalArgumentException("bridge request is required");
        BridgeContract contract = bridgeContract();
        try {
            if (!contract.version().equals(request.bridgeVersion())) {
                throw new BridgeException(BridgeStatus.REJECTED, "BRIDGE_VERSION_INCOMPATIBLE",
                    "Unsupported bridge version: " + request.bridgeVersion());
            }
            if (!contract.supports(request.operation())) {
                throw new BridgeException(BridgeStatus.REJECTED, "BRIDGE_OPERATION_UNSUPPORTED",
                    "Unsupported bridge operation: " + request.operation());
            }
            if (request.scope().requestId() != null
                && !request.requestId().equals(request.scope().requestId())) {
                throw new BridgeException(BridgeStatus.REJECTED, "BRIDGE_SCOPE_MISMATCH",
                    "Bridge requestId does not match Kernel data scope");
            }
            contract.dataBoundary().validate(request.scope(), request.requestedReadData(),
                request.requestedWriteData());
            return response(request, BridgeStatus.SUCCESS, exchangePayload(request), null, null);
        } catch (BridgeException failure) {
            return response(request, failure.status(), null, failure.code(), failure.getMessage());
        } catch (KernelViolationException violation) {
            return response(request, BridgeStatus.REJECTED, null, violation.code(), violation.getMessage());
        } catch (RuntimeException failure) {
            return response(request, BridgeStatus.FAILURE, null, "BRIDGE_EXECUTION_FAILED",
                failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
        }
    }

    protected abstract O exchangePayload(BridgeRequest<I> request);

    private BridgeResponse<O> response(BridgeRequest<I> request, BridgeStatus status, O data,
                                       String errorCode, String errorMessage) {
        return new BridgeResponse<>(bridgeContract().version(), request.requestId(), status, data,
            errorCode, errorMessage, request.metadata(), System.currentTimeMillis());
    }
}
