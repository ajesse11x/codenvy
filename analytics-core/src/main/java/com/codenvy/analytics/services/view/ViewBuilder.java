/*
 *
 * CODENVY CONFIDENTIAL
 * ________________
 *
 * [2012] - [2013] Codenvy, S.A.
 * All Rights Reserved.
 * NOTICE: All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any. The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.analytics.services.view;

import com.codenvy.analytics.Utils;
import com.codenvy.analytics.datamodel.ValueData;
import com.codenvy.analytics.metrics.Parameters;
import com.codenvy.analytics.services.ConfigurationManager;
import com.codenvy.analytics.services.Feature;
import com.codenvy.analytics.services.XmlConfigurationManager;
import com.codenvy.analytics.storage.CSVDataPersister;
import com.codenvy.analytics.storage.DataPersister;
import com.codenvy.analytics.storage.JdbcDataPersisterFactory;
import com.codenvy.dto.server.JsonStringMapImpl;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;

/** @author <a href="mailto:areshetnyak@codenvy.com">Alexander Reshetnyak</a> */
@Path("view")
public class ViewBuilder implements Feature {

    /** Logger. */
    private static final Logger LOG           = LoggerFactory.getLogger(ViewBuilder.class);
    private static final String VIEW_RESOURCE = "views.xml";

    private final DataPersister                              dataPersister;
    private final CSVDataPersister                           csvDataPersister;
    private final ConfigurationManager<DisplayConfiguration> configurationManager;

    public ViewBuilder() {
        this.configurationManager = new XmlConfigurationManager<>(DisplayConfiguration.class);
        this.dataPersister = JdbcDataPersisterFactory.getDataPersister();
        this.csvDataPersister = new CSVDataPersister();
    }

    /** {@inheritDoc} */
    @Override
    public boolean isAvailable() {
        return true;
    }

    @GET
    @Path("build/{name}")
    @Produces({"application/json"})
    public Response build(@PathParam("name") String name, @Context UriInfo uriInfo) {
        try {
            DisplayConfiguration displayConfiguration = configurationManager.loadConfiguration(VIEW_RESOURCE);
            ViewConfiguration viewConfiguration = displayConfiguration.getView(name);

            Map<String, String> context = extractContext(uriInfo);

            Map<String, List<List<ValueData>>> result;
            if (Utils.getFilters(context).isEmpty()) {
                result = queryViewData(viewConfiguration, context);
            } else {
                result = computeViewData(viewConfiguration, context);
            }

            return Response.status(Response.Status.OK).entity(transform(result).toJson()).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
    }

    private JsonStringMapImpl transform(Map<String, List<List<ValueData>>> data) {
        Map<String, Map<String, Map<String, String>>> result = new LinkedHashMap<>(data.size());

        for (Map.Entry<String, List<List<ValueData>>> sectionEntry : data.entrySet()) {
            Map<String, Map<String, String>> newSectionData = new LinkedHashMap<>(sectionEntry.getValue().size());

            for (int i = 0; i < sectionEntry.getValue().size(); i++) {
                List<ValueData> rowData = sectionEntry.getValue().get(i);
                Map<String, String> newRowData = new LinkedHashMap<>(rowData.size());

                for (int j = 0; j < rowData.size(); j++) {
                    newRowData.put("c" + j, rowData.get(j).getAsString());
                }

                newSectionData.put("r" + i, newRowData);
            }

            result.put(sectionEntry.getKey(), newSectionData);
        }

        return new JsonStringMapImpl(result);
    }

    private Map<String, String> extractContext(UriInfo info) {
        MultivaluedMap<String, String> parameters = info.getQueryParameters();
        Map<String, String> context = new HashMap<>(parameters.size());

        for (String key : parameters.keySet()) {
            context.put(key.toUpperCase(), parameters.getFirst(key));
        }

        return context;
    }

    /** {@inheritDoc} */
    @Override
    public void forceExecute(Map<String, String> context) throws JobExecutionException {
        try {
            doExecute();
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
        try {
            doExecute();
        } catch (Exception e) {
            throw new JobExecutionException(e);
        }
    }

    /**
     * Compute data for specific view.
     *
     * @return result in format: key - section id, value - data of this section
     */
    private Map<String, List<List<ValueData>>> queryViewData(ViewConfiguration viewConfiguration,
                                                             Map<String, String> context) throws IOException {
        try {
            Map<String, List<List<ValueData>>> viewData = new LinkedHashMap<>(viewConfiguration.getSections().size());
            Parameters.TimeUnit timeUnit = Utils.getTimeUnit(context);

            for (SectionConfiguration sectionConfiguration : viewConfiguration.getSections()) {




                List<List<ValueData>> sectionData = new ArrayList<>(sectionConfiguration.getRows().size());

                for (RowConfiguration rowConfiguration : sectionConfiguration.getRows()) {
                    Constructor<?> constructor =
                            Class.forName(rowConfiguration.getClazz()).getConstructor(Map.class);
                    Row row = (Row)constructor.newInstance(rowConfiguration.getParamsAsMap());

                    int rowCount = timeUnit == Parameters.TimeUnit.LIFETIME ? 2 : sectionConfiguration.getColumns();
                    Map<String, String> initialContext = Utils.initializeContext(timeUnit);

                    List<ValueData> rowData = row.getData(initialContext, rowCount);
                    sectionData.add(rowData);
                }

                String sectionId = sectionConfiguration.getName() + "_" + timeUnit.toString().toLowerCase();
                viewData.put(sectionId, sectionData);
            }

            return viewData;
        } catch (NoSuchMethodException | ClassCastException | ClassNotFoundException | InvocationTargetException |
                IllegalAccessException | InstantiationException | ParseException e) {
            throw new IOException(e);
        }
    }

    /**
     * Compute data for specific view.
     *
     * @return result in format: key - section id, value - data of this section
     */
    private Map<String, List<List<ValueData>>> computeViewData(ViewConfiguration viewConfiguration,
                                                               Map<String, String> context) throws IOException {
        try {
            Map<String, List<List<ValueData>>> viewData = new LinkedHashMap<>(viewConfiguration.getSections().size());
            Parameters.TimeUnit timeUnit = Utils.getTimeUnit(context);

            for (SectionConfiguration sectionConfiguration : viewConfiguration.getSections()) {

                List<List<ValueData>> sectionData = new ArrayList<>(sectionConfiguration.getRows().size());

                for (RowConfiguration rowConfiguration : sectionConfiguration.getRows()) {
                    Constructor<?> constructor =
                            Class.forName(rowConfiguration.getClazz()).getConstructor(Map.class);
                    Row row = (Row)constructor.newInstance(rowConfiguration.getParamsAsMap());

                    int rowCount = timeUnit == Parameters.TimeUnit.LIFETIME ? 2 : sectionConfiguration.getColumns();
                    Map<String, String> initialContext = Utils.initializeContext(timeUnit);

                    List<ValueData> rowData = row.getData(initialContext, rowCount);
                    sectionData.add(rowData);
                }

                String sectionId = sectionConfiguration.getName() + "_" + timeUnit.toString().toLowerCase();
                viewData.put(sectionId, sectionData);
            }

            return viewData;
        } catch (NoSuchMethodException | ClassCastException | ClassNotFoundException | InvocationTargetException |
                IllegalAccessException | InstantiationException | ParseException e) {
            throw new IOException(e);
        }
    }

    protected void doExecute() throws Exception {
        LOG.info("ViewBuilder is started");
        long start = System.currentTimeMillis();

        try {
            computeDisplayData(configurationManager.loadConfiguration(VIEW_RESOURCE));
        } finally {
            LOG.info("ViewBuilder is finished in " + (System.currentTimeMillis() - start) / 1000 + " sec.");
        }
    }

    protected void computeDisplayData(DisplayConfiguration displayConfiguration) throws Exception {
        List<RecursiveAction> tasks = new ArrayList<>();

        ForkJoinPool forkJoinPool = new ForkJoinPool();

        for (ViewConfiguration viewConfiguration : displayConfiguration.getViews()) {
            if (!viewConfiguration.isOnDemand()) {
                for (String timeUnitParam : viewConfiguration.getTimeUnit().split(",")) {
                    Parameters.TimeUnit timeUnit = Parameters.TimeUnit.valueOf(timeUnitParam.toUpperCase());

                    ComputeViewDataAction task = new ComputeViewDataAction(viewConfiguration, timeUnit);
                    forkJoinPool.submit(task);

                    tasks.add(task);
                }
            }
        }

        forkJoinPool.shutdown();
        forkJoinPool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);

        for (RecursiveAction task : tasks) {
            if (task.getException() != null) {
                throw new IllegalStateException(task.getException());
            } else if (!task.isDone()) {
                throw new IllegalStateException("Task wasn't done");

            }
        }
    }

    protected void retainViewData(String viewId,
                                  Map<String, List<List<ValueData>>> viewData,
                                  Map<String, String> context) throws SQLException, IOException {

        dataPersister.storeData(viewId, viewData, context);
        csvDataPersister.storeData(viewId, viewData, context);
    }

    private class ComputeViewDataAction extends RecursiveAction {

        private final ViewConfiguration   viewConfiguration;
        private final Parameters.TimeUnit timeUnit;

        private ComputeViewDataAction(ViewConfiguration viewConfiguration, Parameters.TimeUnit timeUnit) {
            this.viewConfiguration = viewConfiguration;
            this.timeUnit = timeUnit;
        }

        @Override
        protected void compute() {
            try {
                String viewId = viewConfiguration.getName() + "_" + timeUnit.toString().toLowerCase();
                Map<String, String> context = Utils.initializeContext(timeUnit);

                Map<String, List<List<ValueData>>> viewData = computeViewData(viewConfiguration, context);

                retainViewData(viewId, viewData, Utils.initializeContext(Parameters.TimeUnit.DAY)); // TODO context
            } catch (IOException | ParseException | SQLException e) {
                LOG.error(e.getMessage(), e);
                throw new IllegalStateException(e);
            }
        }
    }
}
