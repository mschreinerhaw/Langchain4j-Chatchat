package com.chatchat.api.datascience.sql;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Runs one fixed reviewed parser program, never a shell or model-supplied Python/SQL. */
@Component
public final class SqlGlotToolchain {
    public static final class UnconfirmedStopException extends IllegalStateException {
        public UnconfirmedStopException() { super("SQL parser stop was not confirmed; reconcile before retry"); }
    }
    private final String python;
    private final ObjectMapper json;
    private volatile Path script;
    public SqlGlotToolchain(@Value("${chatchat.coding.workspace.sqlglot-python:}") String python, ObjectMapper json) {
        this.python=python; this.json=json;
    }
    public boolean available() { return python != null && !python.isBlank(); }
    public Map<String,Object> validate(String dialect, List<SqlOutputBundle.Candidate> files, Map<String,Object> schema) {
        if (!available()) throw new IllegalStateException("SQLGlot interpreter is not configured");
        Process process=null; Path output=null; Path errors=null;
        try {
            // Redirect bounded parser output to temporary files so neither pipe can deadlock.
            output=Files.createTempFile("sqlglot-output-", ".json");
            errors=Files.createTempFile("sqlglot-errors-", ".txt");
            process=new ProcessBuilder(python,"-I",script().toString()).redirectOutput(output.toFile())
                .redirectError(errors.toFile()).start();
            byte[] input=json.writeValueAsBytes(Map.of("dialect",dialect,"files",files,"schema",schema));
            if(input.length>1024*1024)throw new IllegalArgumentException("SQL parser input exceeds limit");
            try(var stream=process.getOutputStream()){stream.write(input);}
            if(!process.waitFor(10,TimeUnit.SECONDS))throw new IllegalStateException("SQL parser timed out");
            if(process.exitValue()!=0)throw new IllegalStateException("SQLGlot parser unavailable or failed; no SQL was executed");
            if(Files.size(output)>2*1024*1024)throw new IllegalStateException("SQL parser output exceeds limit");
            Map<String,Object> result=json.readValue(Files.readAllBytes(output),new TypeReference<Map<String,Object>>(){});
            if(!"30.23.0".equals(result.get("version")))throw new IllegalStateException("SQLGlot version differs from the pinned workspace environment");
            return result;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("SQL parsing interrupted",interrupted);}
        catch(java.io.IOException failure){throw new IllegalStateException("SQL parser could not run",failure);}
        finally {
            if(process!=null && process.isAlive()){
                process.destroyForcibly();
                boolean interrupted=Thread.interrupted();
                try{if(!process.waitFor(2,TimeUnit.SECONDS))throw new UnconfirmedStopException();}
                catch(InterruptedException stopInterrupted){interrupted=true;if(process.isAlive())throw new UnconfirmedStopException();}
                finally{if(interrupted)Thread.currentThread().interrupt();}
            }
            for(Path file:Arrays.asList(output,errors))if(file!=null)try{Files.deleteIfExists(file);}catch(java.io.IOException ignored){}
        }
    }
    private synchronized Path script() throws java.io.IOException {
        if(script==null){
            Path prepared=Files.createTempFile("runtime-sqlglot-", ".py");
            try(var input=new ClassPathResource("sql-toolchain/validate.py").getInputStream()){
                Files.copy(input,prepared,StandardCopyOption.REPLACE_EXISTING);
            }
            prepared.toFile().deleteOnExit();script=prepared;
        }
        return script;
    }
}
