package com.chatchat.common.bridge;

/** Root port for transport adapters communicating with the Runtime OS. */
public interface RuntimeBridge<I, O> {
    BridgeContract bridgeContract();

    BridgeResponse<O> exchange(BridgeRequest<I> request);
}
