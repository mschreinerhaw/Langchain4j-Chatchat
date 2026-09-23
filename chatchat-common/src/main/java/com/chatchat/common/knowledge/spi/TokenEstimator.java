package com.chatchat.common.knowledge.spi;

/** Estimates model input tokens without coupling the Runtime contract to one tokenizer. */
@FunctionalInterface
public interface TokenEstimator {

    int estimate(String value);
}
