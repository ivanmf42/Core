/**
 * Danta Core Bundle
 *
 * Copyright (C) 2017 Tikal Technologies, Inc. All rights reserved.
 *
 * Licensed under GNU Affero General Public License, Version v3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.gnu.org/licenses/agpl-3.0.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied;
 * without even the implied warranty of MERCHANTABILITY.
 * See the License for more details.
 */

package danta.core.templating;

import com.github.jknack.handlebars.Context;
import danta.api.TemplateContentModel;
import danta.core.commons.collections.POJOBackedMap;
import net.minidev.json.JSONObject;

import java.util.*;

import static danta.Constants.BLANK;
import static danta.core.util.ObjectUtils.wrap;

/**
 * Template Content Model Implementer
 *
 * @author      joshuaoransky
 * @version     1.0.0
 * @since       2013-11-08
 */
public abstract class AbstractTemplateContentModelImpl
        implements TemplateContentModel {

    private volatile Context currentContext;
    private final Context rootContext;
    private volatile String cachedJSONString = null;

    public enum ScopeLocality {
        ROOT,
        CLOSEST,
        ISOLATED
    }

    public Context getRootContext() {

        return rootContext;
    }

    public AbstractTemplateContentModelImpl(final Map<String, Object> initialModelData) {
        currentContext = rootContext = Context.newBuilder((initialModelData == null) ? new JSONObject() : wrap(initialModelData)).build();
    }

    public AbstractTemplateContentModelImpl set(final String path, final Object value, final ScopeLocality locality) {
        switch (locality) {
            case ROOT:
                return set(rootContext(), path, value);
            case ISOLATED:
                List<String> parsedPath = parsePath(path);
                JSONObject isolatedModelData = new JSONObject();
                for (String name : ancestors(parsedPath)) {
                    isolatedModelData.put(name, new JSONObject());
                }
                isolatedModelData.put(targetKey(parsedPath), value);
                return isolateToCurrentScope(isolatedModelData);
            case CLOSEST:
            default:
                return set(currentContext, path, value);
        }
    }

    /**
     * Set Property name to value. This has two different effects depending on the state of the current Scope in the
     * ContentModel. If it's a normal member of the Scope hierarchy, then an attempt is made to find if any ancestor of
     * the key is already somewhere in the Scope hierarchy. This continues by back tracing the key until either one is
     * found, or the root Scope is reached with no existing ancestor. The key and any missing intermediates then are
     * created on the closest Scope found, or the current local one if the entire key structure is unique.
     * <p/>
     * For example, consider a key like <code>lists.GoT.Starks.killed</code> and value of "Eddard".
     *
     * @param path
     * @param value
     *
     * @return
     */

    @Override
    public AbstractTemplateContentModelImpl set(final String path, final Object value) {
        return set(path, value, ScopeLocality.CLOSEST);
    }

    public AbstractTemplateContentModelImpl setAsIsolated(final String path, final Object value) {
        return set(path, value, ScopeLocality.ISOLATED);
    }

    /**
     * Set Attribute name to value. Attributes are not included in static representations of the ContentModel.
     * For example, a JSON representation provided to clients. They are also not included in any list of model keys or
     * values, and can only be retrieved by using their explicit key using getAttribute(), or using the standard get()
     * with @ prepended to the key name. This makes them useful for sharing information between ContextProcessors
     * without worrying that it might be accidentally sent to clients or casually provided publicly as part of a key
     * Set.
     * <p/>
     * There's an important difference between attributes and properties regarding Scopes. Unlike data Properties, they
     * are not scoped, and will persist even if all Scopes (up to the root, of course) have been destroyed.
     * <p/>
     *
     * @param name
     *         The name of the Attribute
     * @param value
     *         The value of the Attribute
     *
     * @return This instances of the TemplateContentModelImpl to allow chaining
     */
    @Override
    public AbstractTemplateContentModelImpl setAttribute(final String name, final Object value) {
        currentContext.data(name, value);
        return this;
    }

    /**
     * @param name
     *
     * @return The Value of the Attribute as an Object
     */
    @Override
    public Object getAttribute(final String name) {
        return currentContext.data(name);
    }

    @Override
    public String getAsString(final String name) {
        Object value = get(name);
        return (value != null) ? value.toString() : BLANK;
    }

    @Override
    public Object get(final String name) {
        return currentContext.get(name);
    }

    @Override
    public <T> T getAs(final String name, final Class<T> type)
            throws Exception {
        return (is(name, type)) ? (T) currentContext.get(name) : null;
    }

    @Override
    public boolean has(final String name) {
        return (name != null && !name.isEmpty() && currentContext.get(name) != null);
    }

    @Override
    public <T> boolean is(String name, Class<T> type) {
        return (has(name) && type != null && type.isAssignableFrom(get(name).getClass()));
    }

    @Override
    public JSONObject toJSONObject(String... keys) {
        JSONObject modelDataObj = new JSONObject();
        for (String key : keys) {
            Object value = get(key);
            if (value != null)
                modelDataObj.put(key, value);
        }
        return modelDataObj;
    }

    public JSONObject toJSONObject() {
        synchronized (currentScopeData()) {
            JSONObject modelDataObj = wrap(currentScopeData());
            Context context = currentContext.parent();
            while (context != null) {
                modelDataObj.merge(context.model());
                context = context.parent();
            }
            return modelDataObj;
        }
    }

    public String toJSONString() {
        if (cachedJSONString == null) {
            cachedJSONString = toJSONObject().toJSONString();
        }

        return toJSONObject().toString();
    }

    @Override
    public String toString() {
        return toJSONString();
    }

    public synchronized AbstractTemplateContentModelImpl extendScope() {
        currentContext = newChildContext();
        invalidateJSONString();
        return this;
    }

    public synchronized AbstractTemplateContentModelImpl retractScope() {
        if (currentContext != rootContext) {
            Context oldContext = currentContext;
            currentContext = currentContext.parent();
            oldContext.destroy();
        }
        invalidateJSONString();
        return this;
    }

    public Context handlebarsContext() {
        return currentContext;
    }

    private AbstractTemplateContentModelImpl isolateToCurrentScope(final Map<String, Object> isolatedModelData) {
        currentScopeData().merge(wrap(isolatedModelData));
        invalidateJSONString();
        return this;
    }

    private Context rootContext() {
        return rootContext;
    }

    private JSONObject currentScopeData() {
        return scopeDataFor(currentContext);
    }

    private JSONObject scopeDataFor(Context context) {
        return (JSONObject) context.model();
    }

    private synchronized AbstractTemplateContentModelImpl set(final Context context, final String path, final Object value) {
        List<String> keys = parsePath(path);
        StringBuilder builtPath = new StringBuilder();
        Map<String, Object> modelDataObj = scopeDataFor(context);
        List<String> ancestors = ancestors(keys);
        for (int i = 0; i < ancestors.size(); i++) {
            String key = ancestors.get(i);
            if (i > 0) builtPath.append(".");
            builtPath.append(key);
            Object valueObj = get(builtPath.toString());
            if (valueObj == null) { // Create the value at key.
                valueObj = new JSONObject();
                modelDataObj.put(key, valueObj);
            } else
            if (valueObj instanceof Map) {
                // Perfect... leave it then...
            } else {
                valueObj = new JSONObject();
                modelDataObj.put(key, valueObj); // Replace the value at key.
            }
            modelDataObj = (Map<String, Object>) valueObj;
        }

        modelDataObj.put(targetKey(keys), isValid(value) ? value : POJOBackedMap.toMap(value));
        invalidateJSONString();
        return this;
    }

    private boolean isValid(final Object o) {
        return (o instanceof String || o instanceof Number || o instanceof Date || o instanceof Calendar ||
                o instanceof Collection || o instanceof Map || o instanceof Boolean);
    }

    private Context newChildContext() {
        return Context.newBuilder(currentContext, new JSONObject()).build();
    }

    private List<String> parsePath(final String path) {
        StringTokenizer tokenizer = new StringTokenizer(path, "./");
        int len = tokenizer.countTokens();
        if (len == 1) {
            return Arrays.asList(path);
        }
        List<String> keys = new ArrayList<>(len);
        while (tokenizer.hasMoreTokens()) {
            keys.add(tokenizer.nextToken());
        }
        return keys;
    }

    private String targetKey(final List<String> pathParts) {
        return pathParts.get(pathParts.size() - 1);
    }

    private List<String> ancestors(final List<String> pathParts) {
        return (pathParts.size() > 1) ? pathParts.subList(0, pathParts.size() - 1) : Collections.EMPTY_LIST;
    }

    private void invalidateJSONString() {
        cachedJSONString = null;
    }
}