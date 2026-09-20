package com.chatchat.chat.skills.domain.adapter;

import java.util.List;

/** Platform-owned, model-neutral skill protocol. Runtime never consumes the external document directly. */
public record RuntimeSkillIr(
    String schemaVersion,
    SkillIdentity identity,
    String description,
    SkillTrigger trigger,
    SkillCapability capability,
    SkillInstruction instruction,
    SkillIoContract io,
    SkillExecutionRequirement execution,
    SkillSafetyPolicy safety,
    SkillSourceMetadata source,
    SkillCompilationMetadata compilation,
    String markdownInstructions
) {
    public static final String SCHEMA_VERSION = "runtime_skill_ir.v1";
    public static final String COMPILER_VERSION = "external-skill-compiler.v1";

    public RuntimeSkillIr {
        identity = identity == null ? new SkillIdentity("", "") : identity;
        trigger = trigger == null ? new SkillTrigger("", List.of()) : trigger;
        capability = capability == null ? new SkillCapability("GENERAL", List.of()) : capability;
        instruction = instruction == null
            ? new SkillInstruction("", List.of(), List.of(), List.of(), List.of(), List.of()) : instruction;
        io = io == null ? new SkillIoContract(List.of(), List.of()) : io;
        execution = execution == null ? new SkillExecutionRequirement(List.of()) : execution;
        safety = safety == null ? new SkillSafetyPolicy("UNTRUSTED", List.of()) : safety;
        source = source == null ? new SkillSourceMetadata("EXTERNAL", "", "") : source;
        compilation = compilation == null
            ? new SkillCompilationMetadata(COMPILER_VERSION, "", "DETERMINISTIC_FALLBACK") : compilation;
    }

    /** Compatibility constructor for deterministic adapters and focused tests. */
    public RuntimeSkillIr(String schemaVersion, String name, String description, String markdownInstructions,
                          List<String> capabilities, List<String> riskNotes, String compilationMode) {
        this(schemaVersion, new SkillIdentity(name, name), description,
            new SkillTrigger(description, List.of()), new SkillCapability("GENERAL", capabilities),
            new SkillInstruction(description, List.of(), List.of(markdownInstructions), List.of(), List.of(), List.of()),
            new SkillIoContract(List.of("TEXT"), List.of("TEXT")), new SkillExecutionRequirement(List.of()),
            new SkillSafetyPolicy("UNTRUSTED", riskNotes),
            new SkillSourceMetadata("EXTERNAL", name, "SKILL_MD"),
            new SkillCompilationMetadata(COMPILER_VERSION, "", compilationMode), markdownInstructions);
    }

    public String name() { return identity.name(); }
    public List<String> capabilities() { return capability.actions(); }
    public List<String> riskNotes() { return safety.riskNotes(); }
    public String compilationMode() { return compilation.mode(); }

    public record SkillIdentity(String name, String displayName) { }

    public record SkillTrigger(String description, List<String> semanticTriggers) {
        public SkillTrigger { semanticTriggers = immutable(semanticTriggers); }
    }

    public record SkillCapability(String domain, List<String> actions) {
        public SkillCapability { actions = immutable(actions); }
    }

    public record SkillInstruction(String objective, List<String> principles, List<String> procedures,
                                   List<String> constraints, List<String> validationRules, List<String> examples) {
        public SkillInstruction {
            principles = immutable(principles);
            procedures = immutable(procedures);
            constraints = immutable(constraints);
            validationRules = immutable(validationRules);
            examples = immutable(examples);
        }
    }

    public record SkillIoContract(List<String> inputTypes, List<String> outputTypes) {
        public SkillIoContract { inputTypes = immutable(inputTypes); outputTypes = immutable(outputTypes); }
    }

    public record SkillExecutionRequirement(List<String> requiredCapabilities) {
        public SkillExecutionRequirement { requiredCapabilities = immutable(requiredCapabilities); }
    }

    public record SkillSafetyPolicy(String riskLevel, List<String> riskNotes) {
        public SkillSafetyPolicy { riskNotes = immutable(riskNotes); }
    }

    public record SkillSourceMetadata(String type, String originalName, String originalFormat) { }
    public record SkillCompilationMetadata(String compilerVersion, String model, String mode) { }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
