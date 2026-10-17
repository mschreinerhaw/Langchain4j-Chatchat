package com.chatchat.api.runtime.coding;

import java.time.Duration;
import java.util.concurrent.Executors;

/** Separate JVM fixture over a real file database; no Sandbox or model is invoked. */
public final class CodingRecoveryProcess {
    public static void main(String[] arguments) throws Exception {
        var fixture=new CodingTaskPersistenceTest();
        fixture.workers=Executors.newSingleThreadExecutor(); fixture.timer=Executors.newSingleThreadScheduledExecutor();
        try {
            if(arguments[0].equals("create")) {
                fixture.connect(arguments[1],true); fixture.service.submit(fixture.contract("task",10));
                var lease=fixture.claim("task","departed-jvm");
                fixture.service.reserve(fixture.scope,"task",lease,fixture.rev("task"),"op",fixture.intent,fixture.counters(1,"2"));
                fixture.service.startIntent(fixture.scope,"task",lease,fixture.rev("task"),"op","unconfirmed:external-handle");
                System.out.println("CREATED_IN_SEPARATE_JVM");
            } else {
                fixture.clock.advance(Duration.ofSeconds(11)); fixture.connect(arguments[1],false);
                var lease=fixture.claim("task","replacement-jvm");
                var outcome=fixture.service.resume(fixture.scope,"task");
                System.out.println("RECOVERED|"+lease.fencingToken()+"|"+outcome.executionControl().reconciliationRequired()
                    +"|"+outcome.executionControl().executorStopped()+"|"+outcome.usage().reserved().costAmount());
            }
        } finally { fixture.shutdown(); }
    }
}
