/**
 * Runtime OS summary kernel, split into three explicit responsibilities:
 * {@code model} contains immutable transport values, {@code spi} contains replaceable execution
 * ports, and {@code analysis} contains governed data-analysis contracts and lifecycle rules.
 * The kernel deliberately has no model SDK, Spring, persistence or transport dependency.
 */
package com.chatchat.common.runtime.summary;
