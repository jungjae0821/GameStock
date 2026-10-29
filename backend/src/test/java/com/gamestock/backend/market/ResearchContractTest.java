package com.gamestock.backend.market;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ResearchContractTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void openApiPathsMatchActualControllersAndAllReferencesResolve() throws Exception {
        JsonNode spec=json.readTree(Files.readString(Path.of("../docs/research-api.openapi.json")));
        Set<String> documented=new HashSet<>(),actual=new HashSet<>();
        spec.get("paths").fields().forEachRemaining(path->path.getValue().fieldNames().forEachRemaining(method->documented.add(method.toUpperCase(Locale.ROOT)+" "+path.getKey())));
        for(Class<?> controller:List.of(ResearchController.class,DiscussionController.class,StockTalkController.class)) {
            String prefix=controller.isAnnotationPresent(RequestMapping.class)?controller.getAnnotation(RequestMapping.class).value()[0]:"";
            for(var method:controller.getDeclaredMethods()) {
                if(method.isAnnotationPresent(GetMapping.class))actual.add("GET "+(prefix+method.getAnnotation(GetMapping.class).value()[0]).replaceFirst("^/api",""));
                if(method.isAnnotationPresent(PostMapping.class))actual.add("POST "+(prefix+method.getAnnotation(PostMapping.class).value()[0]).replaceFirst("^/api",""));
                if(method.isAnnotationPresent(PutMapping.class))actual.add("PUT "+(prefix+method.getAnnotation(PutMapping.class).value()[0]).replaceFirst("^/api",""));
                if(method.isAnnotationPresent(PatchMapping.class))actual.add("PATCH "+(prefix+method.getAnnotation(PatchMapping.class).value()[0]).replaceFirst("^/api",""));
                if(method.isAnnotationPresent(DeleteMapping.class))actual.add("DELETE "+(prefix+method.getAnnotation(DeleteMapping.class).value()[0]).replaceFirst("^/api",""));
            }
        }
        assertEquals(actual,documented);references(spec,spec);
    }
    private void references(JsonNode node,JsonNode root) {
        if(node.has("$ref"))assertFalse(root.at(node.get("$ref").asText().substring(1)).isMissingNode(),node.toString());
        for(JsonNode child:node)references(child,root);
    }
    @Test void handoffExamplesCompileAsWritten() throws Exception {
        String doc=Files.readString(Path.of("../docs/research-backend.md"));
        var blocks=Pattern.compile("```(json|text)\\n(.*?)\\n```",Pattern.DOTALL).matcher(doc);int compiled=0;
        while(blocks.find()) {
            QuantCompiler.Compilation result=null;
            if(blocks.group(1).equals("text"))result=QuantCompiler.compile("SNSCRIPT",blocks.group(2),null);
            else {
                JsonNode node=json.readTree(blocks.group(2));
                if(node.has("version"))result=QuantCompiler.compile("BLOCKS",null,node);
                if(node.has("code"))result=QuantCompiler.compile(node.get("code").get("language").asText(),node.get("code").get("source").asText(),null);
            }
            if(result!=null) {compiled++;assertTrue(result.valid(),result.diagnostics().toString());}
        }
        assertEquals(3,compiled);
    }
}
