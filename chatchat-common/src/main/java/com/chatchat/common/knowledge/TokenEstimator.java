package com.chatchat.common.knowledge;

/** Estimates model input tokens without coupling the Runtime contract to one tokenizer. */
@FunctionalInterface
public interface TokenEstimator {

    int estimate(String value);
}
