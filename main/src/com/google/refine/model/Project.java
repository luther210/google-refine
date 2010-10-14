package com.google.refine.model;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.refine.RefineServlet;
import com.google.refine.ProjectManager;
import com.google.refine.ProjectMetadata;
import com.google.refine.history.History;
import com.google.refine.process.ProcessManager;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.Pool;

public class Project {
    final static protected Map<String, Class<? extends OverlayModel>> 
        s_overlayModelClasses = new HashMap<String, Class<? extends OverlayModel>>();
    
    static public void registerOverlayModel(String modelName, Class<? extends OverlayModel> klass) {
        s_overlayModelClasses.put(modelName, klass);
    }
    
    final public long                       id;
    final public List<Row>                  rows = new ArrayList<Row>();
    
    final public ColumnModel                columnModel = new ColumnModel();
    final public RecordModel                recordModel = new RecordModel();
    final public Map<String, OverlayModel>  overlayModels = new HashMap<String, OverlayModel>();
    
    final public History                    history;
    
    transient public ProcessManager processManager = new ProcessManager();
    transient private Date _lastSave = new Date();

    final static Logger logger = LoggerFactory.getLogger("project");

    static public long generateID() {
        return System.currentTimeMillis() + Math.round(Math.random() * 1000000000000L);
    }

    public Project() {
        id = generateID();
        history = new History(this);
    }

    protected Project(long id) {
        this.id = id;
        this.history = new History(this);
    }
    
    public void dispose() {
        for (OverlayModel overlayModel : overlayModels.values()) {
            try {
                overlayModel.dispose(this);
            } catch (Exception e) {
                logger.warn("Error signaling overlay model before disposing", e);
            }
        }
    }

    public Date getLastSave(){
        return this._lastSave;
    }
    /**
     * Sets the lastSave time to now
     */
    public void setLastSave(){
        this._lastSave = new Date();
    }

    public ProjectMetadata getMetadata() {
        return ProjectManager.singleton.getProjectMetadata(id);
    }

    public void saveToOutputStream(OutputStream out, Pool pool) throws IOException {
        for (OverlayModel overlayModel : overlayModels.values()) {
            try {
                overlayModel.onBeforeSave(this);
            } catch (Exception e) {
                logger.warn("Error signaling overlay model before saving", e);
            }
        }
        
        Writer writer = new OutputStreamWriter(out);
        try {
            Properties options = new Properties();
            options.setProperty("mode", "save");
            options.put("pool", pool);

            saveToWriter(writer, options);
        } finally {
            writer.flush();
        }
        
        for (OverlayModel overlayModel : overlayModels.values()) {
            try {
                overlayModel.onAfterSave(this);
            } catch (Exception e) {
                logger.warn("Error signaling overlay model after saving", e);
            }
        }
    }

    protected void saveToWriter(Writer writer, Properties options) throws IOException {
        writer.write(RefineServlet.VERSION); writer.write('\n');
        
        writer.write("columnModel=\n"); columnModel.save(writer, options);
        writer.write("history=\n"); history.save(writer, options);
        
        for (String modelName : overlayModels.keySet()) {
            writer.write("overlayModel:");
            writer.write(modelName);
            writer.write("=");
            
            try {
                JSONWriter jsonWriter = new JSONWriter(writer);
                
                overlayModels.get(modelName).write(jsonWriter, options);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            writer.write('\n');
        }
        
        writer.write("rowCount="); writer.write(Integer.toString(rows.size())); writer.write('\n');
        for (Row row : rows) {
            row.save(writer, options); writer.write('\n');
        }
    }
    
    static public Project loadFromReader(
        LineNumberReader reader,
        long id,
        Pool pool
    ) throws Exception {
        long start = System.currentTimeMillis();
        
        /* String version = */ reader.readLine();
        
        Project project = new Project(id);
        int maxCellCount = 0;
        
        String line;
        while ((line = reader.readLine()) != null) {
            int equal = line.indexOf('=');
            String field = line.substring(0, equal);
            String value = line.substring(equal + 1);
            
            // backward compatibility
            if ("protograph".equals(field)) {
                field = "overlayModel:freebaseProtograph";
            }
            
            if ("columnModel".equals(field)) {
                project.columnModel.load(reader);
            } else if ("history".equals(field)) {
                project.history.load(project, reader);
            } else if ("rowCount".equals(field)) {
                int count = Integer.parseInt(value);

                for (int i = 0; i < count; i++) {
                    line = reader.readLine();
                    if (line != null) {
                        Row row = Row.load(line, pool);
                        project.rows.add(row);
                        maxCellCount = Math.max(maxCellCount, row.cells.size());
                    }
                }
            } else if (field.startsWith("overlayModel:")) {
                String modelName = field.substring("overlayModel:".length());
                if (s_overlayModelClasses.containsKey(modelName)) {
                    Class<? extends OverlayModel> klass = s_overlayModelClasses.get(modelName);
                    
                    try {
                        Method loadMethod = klass.getMethod("load", Project.class, JSONObject.class);
                        JSONObject obj = ParsingUtilities.evaluateJsonStringToObject(value);
                    
                        OverlayModel overlayModel = (OverlayModel) loadMethod.invoke(null, project, obj);
                        
                        project.overlayModels.put(modelName, overlayModel);
                    } catch (Exception e) {
                        logger.error("Failed to load overlay model " + modelName);
                    }
                }
            }
        }

        project.columnModel.setMaxCellIndex(maxCellCount - 1);

        logger.info(
            "Loaded project {} from disk in {} sec(s)",id,Long.toString((System.currentTimeMillis() - start) / 1000)
        );

        project.update();

        return project;
    }

    public void update() {
        columnModel.update();
        recordModel.update(this);
    }


    //wrapper of processManager variable to allow unit testing
    //TODO make the processManager variable private, and force all calls through this method
    public ProcessManager getProcessManager() {
        return this.processManager;
    }
}