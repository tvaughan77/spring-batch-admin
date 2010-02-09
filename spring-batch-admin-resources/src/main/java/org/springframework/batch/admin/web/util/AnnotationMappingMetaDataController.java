/*
 * Copyright 2009-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.batch.admin.web.util;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.support.HandlerMethodResolver;
import org.springframework.web.servlet.handler.BeanNameUrlHandlerMapping;
import org.springframework.web.servlet.mvc.annotation.DefaultAnnotationHandlerMapping;
import org.springframework.web.util.UrlPathHelper;

/**
 * Component that discovers request mappings in its application context and
 * reveals their meta data. Any {@link RequestMapping} annotations in controller
 * components at method or type level are discovered.
 * 
 * @author Dave Syer
 * 
 */
@Controller
public class AnnotationMappingMetaDataController implements ApplicationContextAware, InitializingBean {

	private static Log logger = LogFactory.getLog(AnnotationMappingMetaDataController.class);

	private ApplicationContext applicationContext;

	private Set<String> urls;

	private List<ResourceInfo> resources;

	private String servletPath;

	/**
	 * 
	 * @see ApplicationContextAware#setApplicationContext(ApplicationContext)
	 */
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = applicationContext;
	}

	/**
	 * The path that will be added to the model as an attribute ("servletPath")
	 * before rendering. Defaults to the parent servlet path (as defined in the
	 * http servlet request).
	 * 
	 * @param servletPath the servlet path to set
	 */
	public void setServletPath(String servletPath) {
		this.servletPath = servletPath;
	}

	/**
	 * Create the meta data by querying the context for mappings.
	 * 
	 * @see InitializingBean#afterPropertiesSet()
	 */
	public void afterPropertiesSet() throws Exception {

		Map<String, Object> handlerMap = new HashMap<String, Object>();

		DefaultAnnotationHandlerMapping annotationMapping = new DefaultAnnotationHandlerMapping();
		annotationMapping.setApplicationContext(applicationContext);
		annotationMapping.initApplicationContext();
		handlerMap.putAll(annotationMapping.getHandlerMap());

		BeanNameUrlHandlerMapping beanMapping = new BeanNameUrlHandlerMapping();
		beanMapping.setApplicationContext(applicationContext);
		beanMapping.initApplicationContext();
		handlerMap.putAll(beanMapping.getHandlerMap());

		this.urls = findUniqueUrls(handlerMap.keySet());
		this.resources = findMethods(handlerMap, this.urls);

	}

	private List<ResourceInfo> findMethods(Map<String, Object> handlerMap, Set<String> urls) {

		SortedSet<ResourceInfo> result = new TreeSet<ResourceInfo>();

		for (String key : urls) {

			Object handler = handlerMap.get(key);
			@SuppressWarnings("unchecked")
			Class handlerType = ClassUtils.getUserClass(handler);
			HandlerMethodResolver resolver = new HandlerMethodResolver();
			resolver.init(handlerType);

			String[] typeMappings = null;
			RequestMapping typeMapping = AnnotationUtils.findAnnotation(handlerType, RequestMapping.class);
			if (typeMapping != null) {
				typeMappings = typeMapping.value();
			}

			Set<Method> handlerMethods = resolver.getHandlerMethods();
			for (Method method : handlerMethods) {

				RequestMapping mapping = method.getAnnotation(RequestMapping.class);

				Collection<String> computedMappings = new HashSet<String>();
				if (typeMappings != null) {
					computedMappings.addAll(Arrays.asList(typeMappings));
				}

				for (String path : mapping.value()) {
					if (typeMappings != null) {
						for (String parent : computedMappings) {
							if (parent.endsWith("/")) {
								parent = parent.substring(0, parent.length() - 1);
							}
							computedMappings.add(parent + path);
						}
					}
					else {
						computedMappings.add(path);
					}
				}

				logger.debug("Analysing mappings for method:" + method.getName() + ", key:" + key
						+ ", computed mappings: " + computedMappings);
				if (computedMappings.contains(key)) {
					RequestMethod[] methods = mapping.method();
					if (methods != null && methods.length > 0) {
						for (RequestMethod requestMethod : methods) {
							logger
									.debug("Added explicit mapping for path=" + key + "to RequestMethod="
											+ requestMethod);
							result.add(new ResourceInfo(key, requestMethod));
						}
					}
					else {
						logger.debug("Added implicit mapping for path=" + key + "to RequestMethod=GET");
						result.add(new ResourceInfo(key, RequestMethod.GET));
					}
				}

			}

			if (handlerMethods.isEmpty()) {
				result.add(new ResourceInfo(key, RequestMethod.GET));
			}

		}

		return new ArrayList<ResourceInfo>(result);

	}

	private Set<String> findUniqueUrls(Collection<String> inputs) {
		Set<String> result = new HashSet<String>(inputs);
		for (String url : inputs) {
			String extended = url + ".*";
			if (inputs.contains(extended)) {
				result.remove(extended);
			}
			extended = url + "/";
			if (inputs.contains(extended)) {
				result.remove(extended);
			}
		}
		return result;
	}

	/**
	 * Inspect the handler mapping at the level of HTTP {@link RequestMethod}.
	 * Each URI pattern that is mapped can be mapped to multiple request
	 * methods. If the mapping is not explicit this method only returns GET
	 * (even though technically it would respond to POST as well).
	 * 
	 * @param request the current servlet request (used to extract a page
	 * attribute "sevletPath")
	 * 
	 * @return a map of URI pattern to request methods accepted
	 */
	@RequestMapping(value = "/home", method = RequestMethod.GET)
	public @ModelAttribute("resources")
	List<ResourceInfo> getResources(HttpServletRequest request) {

		String servletPath = this.servletPath;
		if (servletPath == null) {
			servletPath = new UrlPathHelper().getServletPath(request);
		}
		request.setAttribute("servletPath", servletPath);
		return resources;
	}

	/**
	 * The set of unique URI patterns mapped, excluding implicit mappings.
	 * Implicit mappings include all the values here plus patterns created from
	 * them by appending "/" (if not already present) and ".*" (if no suffix is
	 * already provided).
	 * 
	 * @return the set of unique URI patterns mapped
	 */
	public Set<String> getUrlPatterns() {
		return urls;
	}

}
