package com.google.gridworks.commands.history;

import java.io.IOException;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.google.gridworks.commands.Command;
import com.google.gridworks.model.AbstractOperation;
import com.google.gridworks.model.Project;
import com.google.gridworks.operations.OperationRegistry;
import com.google.gridworks.process.Process;
import com.google.gridworks.util.ParsingUtilities;

public class ApplyOperationsCommand extends Command {
    
    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        Project project = getProject(request);
        String jsonString = request.getParameter("operations");
        try {
            JSONArray a = ParsingUtilities.evaluateJsonStringToArray(jsonString);
            int count = a.length();
            for (int i = 0; i < count; i++) {
                JSONObject obj = a.getJSONObject(i);
                
                reconstructOperation(project, obj);
            }

            if (project.processManager.hasPending()) {
                respond(response, "{ \"code\" : \"pending\" }");
            } else {
                respond(response, "{ \"code\" : \"ok\" }");
            }
        } catch (JSONException e) {
            respondException(response, e);
        }
    }
    
    protected void reconstructOperation(Project project, JSONObject obj) {
        AbstractOperation operation = OperationRegistry.reconstruct(project, obj);
        if (operation != null) {
            try {
                Process process = operation.createProcess(project, new Properties());
                
                project.processManager.queueProcess(process);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}