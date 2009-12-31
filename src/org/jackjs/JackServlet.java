package org.jackjs;
import java.io.IOException;
import java.io.File;
import javax.servlet.http.*;
import javax.servlet.*;

import java.io.*;

import org.mozilla.javascript.*;

@SuppressWarnings("serial")
public class JackServlet extends HttpServlet {
    private Scriptable scope;
    private Function app;
    private Function handler;

    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        final String modulesPath = getServletContext().getRealPath(getInitParam(config, "modulesPath", "WEB-INF"));
        final String moduleName = getInitParam(config, "module", "jackconfig.js");
        final String appName = getInitParam(config, "app", "app");
        final String environmentName = getInitParam(config, "environment", null);

        final String usingPath = getServletContext().getRealPath("WEB-INF/using");
        final String narwhalHome = getServletContext().getRealPath("WEB-INF/narwhal");
        final String narwhalFilename = "engines/rhino/bootstrap.js";

        Context context = Context.enter();
        try {
            //context.setOptimizationLevel(-1);
            scope = new ImporterTopLevel(context);

            ScriptableObject.putProperty(scope, "NARWHAL_HOME",  Context.javaToJS(narwhalHome, scope));
            ScriptableObject.putProperty(scope, "SEA",  Context.javaToJS(getServletContext().getRealPath("WEB-INF"), scope));
            //ScriptableObject.putProperty(scope, "$DEBUG",  Context.javaToJS(true, scope));

            // load Narwhal
            context.evaluateReader(scope, new FileReader(narwhalHome+"/"+narwhalFilename), narwhalFilename, 1, null);

            // enable wildfire if present
            boolean wildfire = false;
            String wildfirePkgId = "github.com/cadorn/wildfire/zipball/master/packages/lib-js-system";
            try {
                if(new java.io.File(usingPath+"/"+wildfirePkgId).isDirectory()) {
                    wildfire = true;
                    // add wildfire system package to require.paths
                    context.evaluateString(scope, "require.paths.push('" + usingPath + "/" + wildfirePkgId + "/lib');", null, 1, null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // load Servlet handler "process" method
            handler = (Function)context.evaluateString(scope, "require('jack/handler/servlet').Servlet.process;", null, 1, null);

            // load the app
            Scriptable module = (Scriptable)context.evaluateString(scope, "require('"+modulesPath+"/"+moduleName+"');", null, 1, null);

            app = (Function)module.get(appName, module);

            if(wildfire) {
                // pass all responses through the wildfire dispatcher to add headers
                // app = require("wildfire/binding/jack").Dispatcher(app);
                Scriptable wildfireModule = (Scriptable)context.evaluateString(scope, "require('wildfire/binding/jack');", null, 1, null);
                Object args[] = {app};
                app = (Function)((Function)wildfireModule.get("Dispatcher", wildfireModule)).call(context, scope, wildfireModule, args);
            }

            if (environmentName != null) {
                Object environment = module.get(environmentName, module);
                if (environment instanceof Function) {
                    Object args[] = {app};
                    app = (Function)((Function)environment).call(context, scope, module, args);
                } else {
                    System.err.println("Warning: environment named \"" + environmentName + "\" not found or not a function.");
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Context.exit();
        }
    }

    public void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Context context = Context.enter();
        try	{
            Object args[] = {app, request, response};
            handler.call(context, scope, null, args);
        } finally {
            Context.exit();
        }
    }
    
    private String getInitParam(ServletConfig config, String name, String defaultValue) {
        String value = config.getInitParameter(name);
        return value == null ? defaultValue : value;
    }
}
