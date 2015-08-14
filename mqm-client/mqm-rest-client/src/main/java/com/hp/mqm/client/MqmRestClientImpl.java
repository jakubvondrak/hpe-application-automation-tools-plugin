package com.hp.mqm.client;

import com.hp.mqm.client.exception.FileNotFoundException;
import com.hp.mqm.client.exception.RequestErrorException;
import com.hp.mqm.client.exception.RequestException;
import com.hp.mqm.client.internal.InputStreamSourceEntity;
import com.hp.mqm.client.model.Field;
import com.hp.mqm.client.model.FieldMetadata;
import com.hp.mqm.client.model.JobConfiguration;
import com.hp.mqm.client.model.ListItem;
import com.hp.mqm.client.model.PagedList;
import com.hp.mqm.client.model.Pipeline;
import com.hp.mqm.client.model.Release;
import com.hp.mqm.client.model.Taxonomy;
import com.hp.mqm.client.model.TestResultStatus;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONNull;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.StringEntity;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MqmRestClientImpl extends AbstractMqmRestClient implements MqmRestClient {
	private static final Logger logger = Logger.getLogger(MqmRestClientImpl.class.getName());

    public static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssZ";

	private static final String PREFIX_CI = "analytics/ci/";

	private static final String URI_TEST_RESULT_PUSH = PREFIX_CI + "test-results";
	private static final String URI_TEST_RESULT_STATUS = PREFIX_CI + "test-results/{0}";
	private static final String URI_JOB_CONFIGURATION = "analytics/ci/servers/{0}/jobs/{1}/configuration";
	private static final String URI_RELEASES = "releases";
	private static final String URI_LIST_ITEMS = "list_nodes";
	private static final String URI_PUT_EVENTS = "analytics/ci/events";
    private static final String URI_TAXONOMY_NODES = "taxonomy_nodes";

	private static final String HEADER_ACCEPT = "Accept";

	/**
	 * Constructor for AbstractMqmRestClient.
	 *
	 * @param connectionConfig MQM connection configuration, Fields 'location', 'domain', 'project' and 'clientType' must not be null or empty.
	 */
	MqmRestClientImpl(MqmConnectionConfig connectionConfig) {
		super(connectionConfig);
	}

	@Override
	public long postTestResult(InputStreamSource inputStreamSource, boolean skipErrors) {
		return postTestResult(new InputStreamSourceEntity(inputStreamSource, ContentType.APPLICATION_XML));
	}

	@Override
	public long postTestResult(File testResultReport, boolean skipErrors) {
		return postTestResult(new FileEntity(testResultReport, ContentType.APPLICATION_XML));
	}

	@Override
	public TestResultStatus getTestResultStatus(long id) {
		HttpGet request = new HttpGet(createSharedSpaceInternalApiUri(URI_TEST_RESULT_STATUS, id));
        HttpResponse response = null;
        try {
            response = execute(request);
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw createRequestException("Result status retrieval failed", response);
            }
            String json = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            JSONObject jsonObject = JSONObject.fromObject(json);
            Date until = null;
            if (jsonObject.has("until")) {
                try {
                    until = parseDatetime(jsonObject.getString("until"));
                } catch (ParseException e) {
                    throw new RequestException("Cannot obtain status", e);
                }
            }
            return new TestResultStatus(jsonObject.getString("status"), until);
        } catch (IOException e) {
            throw new RequestErrorException("Cannot obtain status.", e);
        } finally {
            HttpClientUtils.closeQuietly(response);
        }
	}

	@Override
	public JobConfiguration getJobConfiguration(String serverIdentity, String jobName) {
		HttpGet request = new HttpGet(createSharedSpaceInternalApiUri(URI_JOB_CONFIGURATION, serverIdentity, jobName));
		HttpResponse response = null;
		try {
			response = execute(request);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				throw createRequestException("Job configuration retrieval failed", response);
			}
			String json = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            try {
                JSONObject jsonObject = JSONObject.fromObject(json);
                List<Pipeline> pipelines = new LinkedList<Pipeline>();
                for (JSONObject relatedContext : getJSONObjectCollection(jsonObject, "data")) {
                    if ("pipeline".equals(relatedContext.getString("contextEntityType"))) {
                        pipelines.add(toPipeline(relatedContext));
                    } else {
                        logger.info("Context type '" + relatedContext.get("contextEntityType") + "' is not supported");
                    }
                }
                return new JobConfiguration(pipelines);
            } catch (JSONException e) {
                throw new RequestException("Failed to obtain job configuration", e);
            }
		} catch (IOException e) {
			throw new RequestErrorException("Cannot retrieve job configuration from MQM.", e);
		} finally {
			HttpClientUtils.closeQuietly(response);
		}
	}

	@Override
	public Pipeline createPipeline(String serverIdentity, String projectName, String pipelineName, long workspaceId, Long releaseId, String structureJson, String serverJson) {
		HttpPost request = new HttpPost(createSharedSpaceInternalApiUri(URI_JOB_CONFIGURATION, serverIdentity, projectName));
		JSONObject pipelineObject = new JSONObject();
		pipelineObject.put("contextEntityType", "pipeline");
		pipelineObject.put("contextEntityName", pipelineName);
		pipelineObject.put("workspaceId", workspaceId);
		pipelineObject.put("releaseId", releaseId);
		pipelineObject.put("server", JSONObject.fromObject(serverJson));
		pipelineObject.put("structure", JSONObject.fromObject(structureJson));
		request.setEntity(new StringEntity(pipelineObject.toString(), ContentType.APPLICATION_JSON));
		HttpResponse response = null;
		try {
			response = execute(request);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_CREATED) {
				throw createRequestException("Pipeline creation failed", response);
			}
			String json = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            return getPipelineByName(json, pipelineName, workspaceId);
		} catch (IOException e) {
			throw new RequestErrorException("Cannot create pipeline in MQM.", e);
		} finally {
			HttpClientUtils.closeQuietly(response);
		}
	}

	@Override
	public void updatePipelineMetadata(String serverIdentity, String projectName, long pipelineId, String pipelineName, long workspaceId, Long releaseId) {
        updatePipeline(serverIdentity, projectName, new Pipeline(pipelineId, pipelineName, null, workspaceId, releaseId, null, null));
	}

	@Override
	public Pipeline updatePipelineTags(String serverIdentity, String jobName, long pipelineId, long workspaceId, List<Taxonomy> taxonomies, List<Field> fields) {
        return updatePipeline(serverIdentity, jobName, new Pipeline(pipelineId, null, null, workspaceId, null, taxonomies, fields));
	}

    @Override
    public Pipeline updatePipeline(String serverIdentity, String jobName, Pipeline pipeline) {
        HttpPut request = new HttpPut(createSharedSpaceInternalApiUri(URI_JOB_CONFIGURATION, serverIdentity, jobName));

        JSONObject pipelineObject = new JSONObject();
        pipelineObject.put("contextEntityType", "pipeline");
        pipelineObject.put("contextEntityId", pipeline.getId());
        pipelineObject.put("workspaceId", pipeline.getWorkspaceId());
        if (pipeline.getName() != null) {
            pipelineObject.put("contextEntityName", pipeline.getName());
        }
        if (pipeline.getReleaseId() != null) {
            if (pipeline.getReleaseId() == -1) {
                pipelineObject.put("releaseId", JSONNull.getInstance());
            } else  {
                pipelineObject.put("releaseId", pipeline.getReleaseId());
            }
        }
        if (pipeline.getTaxonomies() != null) {
            JSONArray taxonomies = taxonomiesArray(pipeline.getTaxonomies());
            pipelineObject.put("taxonomies", taxonomies);
        }
        JSONArray data = new JSONArray();
        data.add(pipelineObject);
        JSONObject payload = new JSONObject();
        payload.put("data", data);

        request.setEntity(new StringEntity(payload.toString(), ContentType.APPLICATION_JSON));
        request.setHeader(HEADER_ACCEPT, ContentType.APPLICATION_JSON.getMimeType());
        HttpResponse response = null;
        try {
            response = execute(request);
            if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
                throw createRequestException("Pipeline update failed", response);
            }
            String json = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            return getPipelineById(json, pipeline.getId());
        } catch (IOException e) {
            throw new RequestErrorException("Cannot update pipeline.", e);
        } finally {
            HttpClientUtils.closeQuietly(response);
        }
    }

    private Date parseDatetime(String datetime) throws ParseException {
        return new SimpleDateFormat(DATETIME_FORMAT).parse(datetime);
    }

    private JSONArray taxonomiesArray(List<Taxonomy> taxonomies) {
        JSONArray ret = new JSONArray();
        for (Taxonomy taxonomy: taxonomies) {
            ret.add(fromTaxonomy(taxonomy));
        }
        return ret;
    }

    private Taxonomy toTaxonomy(JSONObject t) {
        JSONObject parent = t.optJSONObject("parent");
        if (parent != null) {
            return new Taxonomy(t.getLong("id"), t.getString("name"), toTaxonomy(parent));
        } else {
            return new Taxonomy(t.getLong("id"), t.getString("name"), null);
        }
    }

    private JSONObject fromTaxonomy(Taxonomy taxonomy) {
        JSONObject t = new JSONObject();
        if (taxonomy.getId() != null) {
            t.put("id", taxonomy.getId());
        }
        if (taxonomy.getName() != null) {
            t.put("name", taxonomy.getName());
        }
        if (taxonomy.getRoot() != null) {
            t.put("parent", fromTaxonomy(taxonomy.getRoot()));
        } else {
            t.put("parent", JSONNull.getInstance());
        }
        return t;
    }

    private Pipeline getPipelineByName(String json, String pipelineName, long workspaceId) {
        try {
            for (JSONObject item : getJSONObjectCollection(JSONObject.fromObject(json), "data")) {
                if (!"pipeline".equals(item.getString("contextEntityType"))) {
                    continue;
                }
                if (!item.getBoolean("pipelineRoot")) {
                    continue;
                }
                if (!pipelineName.equals(item.getString("contextEntityName"))) {
                    continue;
                }
                if (workspaceId != item.getLong("workspaceId")) {
                    continue;
                }
                return toPipeline(item);
            }
            throw new RequestException("Failed to obtain pipeline: item not found");
        } catch (JSONException e) {
            throw new RequestException("Failed to obtain pipeline", e);
        }
    }

    private Pipeline getPipelineById(String json, long pipelineId) {
        try {
            for (JSONObject item : getJSONObjectCollection(JSONObject.fromObject(json), "data")) {
                if (!"pipeline".equals(item.getString("contextEntityType"))) {
                    continue;
                }
                if (pipelineId != item.getLong("contextEntityId")) {
                    continue;
                }
                return toPipeline(item);
            }
            throw new RequestException("Failed to obtain pipeline: item not found");
        } catch (JSONException e) {
            throw new RequestException("Failed to obtain pipeline", e);
        }
    }

    private RequestException createRequestException(String message, HttpResponse response) {
        return new RequestException(message + "; status code " + response.getStatusLine().getStatusCode() + " and reason " + response.getStatusLine().getReasonPhrase() + " [" + tryParseMessage(response) + "]");
    }

    private String tryParseMessage(HttpResponse response) {
        try {
            String json = IOUtils.toString(response.getEntity().getContent(), "UTF-8");
            JSONObject jsonObject = JSONObject.fromObject(json);
            if (jsonObject.has("error_code") && jsonObject.has("description")) {
                // exception response
                return jsonObject.getString("description");
            }
            if (jsonObject.has("message")) {
                return jsonObject.getString("message");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to determine failure message: ", e);
        } catch (JSONException e) {
            logger.log(Level.SEVERE, "Unable to determine failure message: ", e);
        }
        return "";
    }

    private List<FieldMetadata> getFieldsMetadata(JSONObject metadata) {
		List<FieldMetadata> fields = new LinkedList<FieldMetadata>();
		for (JSONObject fieldObject : getJSONObjectCollection(metadata, "lists")) {
			fields.add(toFieldMetadata(fieldObject));
		}
		return fields;
	}

	private Field toField(JSONObject field) {
		return new Field(field.getInt("id"),
				field.getString("name"),
				field.getInt("parentId"),
				field.getString("parentName"),
				field.getString("parentLogicalName"));
	}

	private FieldMetadata toFieldMetadata(JSONObject field) {
		return new FieldMetadata(
				field.getInt("id"),
				field.getString("name"),
				field.getString("logicalName"),
				field.getBoolean("openList"),
				field.getBoolean("multiValueList"));
	}

	private Pipeline toPipeline(JSONObject pipelineObject) {
		List<Taxonomy> taxonomies = new LinkedList<Taxonomy>();
		List<Field> fields = new LinkedList<Field>();

		if (pipelineObject.has("taxonomies")) {
			for (JSONObject taxonomy : getJSONObjectCollection(pipelineObject, "taxonomies")) {
                taxonomies.add(toTaxonomy(taxonomy));
			}
		}
		if (pipelineObject.has("tags")) {
			for (JSONObject field : getJSONObjectCollection(pipelineObject, "tags")) {
				fields.add(toField(field));
			}
		}
		return new Pipeline(pipelineObject.getLong("contextEntityId"),
				pipelineObject.getString("contextEntityName"),
                pipelineObject.getBoolean("pipelineRoot"),
                pipelineObject.getLong("workspaceId"),
				pipelineObject.has("releaseId") && !pipelineObject.get("releaseId").equals(JSONNull.getInstance()) ? pipelineObject.getLong("releaseId") : null,
				taxonomies, fields);
	}

	@Override
	public PagedList<Release> queryReleases(String name, long workspaceId, int offset, int limit) {
		List<String> conditions = new LinkedList<String>();
		if (!StringUtils.isEmpty(name)) {
			conditions.add(condition("name", "*" + name + "*"));
		}
		return getEntities(getEntityURI(URI_RELEASES, conditions, workspaceId, offset, limit), offset, new ReleaseEntityFactory());
	}

	@Override
	public PagedList<Taxonomy> queryTaxonomyItems(Long taxonomyRootId, String name, long workspaceId, int offset, int limit) {
		List<String> conditions = new LinkedList<String>();
		if (!StringUtils.isEmpty(name)) {
			conditions.add(condition("name", "*" + name + "*"));
		}
		if (taxonomyRootId != null) {
			conditions.add(condition("taxonomy_root.id", String.valueOf(taxonomyRootId)));
		}
        conditions.add(condition("subtype", "taxonomy_item_node"));
		return getEntities(getEntityURI(URI_TAXONOMY_NODES, conditions, workspaceId, offset, limit), offset, new TaxonomyEntityFactory());
	}

	@Override
	public PagedList<Taxonomy> queryTaxonomyCategories(String name, long workspaceId, int offset, int limit) {
		List<String> conditions = new LinkedList<String>();
		if (!StringUtils.isEmpty(name)) {
			conditions.add(condition("name", "*" + name + "*"));
		}
        conditions.add(condition("subtype", "taxonomy_category_node"));
		return getEntities(getEntityURI(URI_TAXONOMY_NODES, conditions, workspaceId, offset, limit), offset, new TaxonomyEntityFactory());
	}

    @Override
    public PagedList<Taxonomy> queryTaxonomies(String name, long workspaceId, int offset, int limit) {
        List<String> conditions = new LinkedList<String>();
        if (!StringUtils.isEmpty(name)) {
            conditions.add(condition("name", "*" + name + "*") + "||" + condition("taxonomy_root.name", "*" + name + "*"));
        }
        return getEntities(getEntityURI(URI_TAXONOMY_NODES, conditions, workspaceId, offset, limit), offset, new TaxonomyEntityFactory());
    }

    @Override
	public PagedList<ListItem> queryListItems(int listId, String name, long workspaceId, int offset, int limit) {
		List<String> conditions = new LinkedList<String>();
		if (!StringUtils.isEmpty(name)) {
			conditions.add(condition("name", "*" + name + "*"));
		}
		conditions.add(condition("list_root.id", String.valueOf(listId)));
		return getEntities(getEntityURI(URI_LIST_ITEMS, conditions, workspaceId, offset, limit), offset, new ListItemEntityFactory());
	}

	private long postTestResult(HttpEntity entity) {
        HttpPost request = new HttpPost(createSharedSpaceInternalApiUri(URI_TEST_RESULT_PUSH));
        request.setEntity(entity);
		HttpResponse response = null;
		try {
			response = execute(request);
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_ACCEPTED) {
                throw createRequestException("Test result post failed", response);
			}
            String json = IOUtils.toString(response.getEntity().getContent());
            JSONObject jsonObject =  JSONObject.fromObject(json);
            return jsonObject.getLong("id");
        } catch (java.io.FileNotFoundException e) {
			throw new FileNotFoundException("Cannot find test result file.", e);
		} catch (IOException e) {
			throw new RequestErrorException("Cannot post test results to MQM.", e);
		} finally {
			HttpClientUtils.closeQuietly(response);
		}
	}

    @Override
	public boolean putEvents(String eventsJSON) {
		HttpPut request = new HttpPut(createSharedSpaceInternalApiUri(URI_PUT_EVENTS));
		request.setEntity(new StringEntity(eventsJSON, ContentType.APPLICATION_JSON));
		HttpResponse response = null;
		boolean result = true;
		try {
			response = execute(request);
			if (response.getStatusLine().getStatusCode() == HttpStatus.SC_TEMPORARY_REDIRECT) {
				// ad-hoc handling as requested by Jenkins Insight team
				HttpClientUtils.closeQuietly(response);
				login();
				response = execute(request);
			}
			if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
				logger.severe("put request failed while sending events: " + response.getStatusLine().getStatusCode());
				result = false;
			}
		} catch (Exception e) {
			logger.severe("put request failed while sending events: " + e.getClass().getName());
			result = false;
		} finally {
			HttpClientUtils.closeQuietly(response);
		}
		return result;
	}

	private static class ListItemEntityFactory extends AbstractEntityFactory<ListItem> {

		@Override
		public ListItem doCreate(JSONObject entityObject) {
			return new ListItem(
					entityObject.getInt("id"),
					entityObject.getString("name"));
		}
	}

	private static class TaxonomyEntityFactory extends AbstractEntityFactory<Taxonomy> {

		@Override
		public Taxonomy doCreate(JSONObject entityObject) {
            JSONObject taxonomy_root = entityObject.optJSONObject("taxonomy_root");
            if (taxonomy_root != null) {
                return new Taxonomy(entityObject.getLong("id"), entityObject.getString("name"), doCreate(taxonomy_root));
            } else {
                return new Taxonomy(entityObject.getLong("id"), entityObject.getString("name"), null);
            }
		}
	}

	private static class ReleaseEntityFactory extends AbstractEntityFactory<Release> {

		@Override
		public Release doCreate(JSONObject entityObject) {
			return new Release(entityObject.getLong("id"), entityObject.getString("name"));
		}
	}

	private static abstract class AbstractEntityFactory<E> implements EntityFactory<E> {

		@Override
		public E create(String json) {
			JSONObject jsonObject = JSONObject.fromObject(json);
			return doCreate(jsonObject);
		}

		public abstract E doCreate(JSONObject entityObject);

	}
}
