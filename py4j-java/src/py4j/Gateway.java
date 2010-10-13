/**
 * Copyright (c) 2009, 2010, Barthelemy Dagenais All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 * 
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * 
 * - The name of the author may not be used to endorse or promote products
 * derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package py4j;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import py4j.reflection.MethodInvoker;
import py4j.reflection.ReflectionEngine;

/**
 * 
 * <p>
 * A Gateway manages various states: entryPoint, references to objects returned
 * to a Python program, etc.
 * </p>
 * 
 * <p>
 * This class is not intended to be directly accessed by users.
 * </p>
 * 
 * @author Barthelemy Dagenais
 * 
 */
public class Gateway {

	private final Map<String, Object> bindings = new ConcurrentHashMap<String, Object>();
	private final AtomicInteger objCounter = new AtomicInteger();
	private final AtomicInteger argCounter = new AtomicInteger();
	private final static String OBJECT_NAME_PREFIX = "o";
	private final Object entryPoint;
	private final ReflectionEngine rEngine = new ReflectionEngine();
	private final CallbackClient cbClient;
	private final JVMView defaultJVMView;

	private final Logger logger = Logger.getLogger(Gateway.class.getName());

	private boolean isStarted = false;

	public Gateway(Object entryPoint) {
		this(entryPoint, null);
	}

	public Gateway(Object entryPoint, CallbackClient cbClient) {
		this.entryPoint = entryPoint;
		this.cbClient = cbClient;
		this.defaultJVMView = new JVMView("default",
				Protocol.DEFAULT_JVM_OBJECT_ID);
	}

	/**
	 * <p>
	 * Called when a connection is closed.
	 * </p>
	 */
	public void closeConnection() {
		logger.info("Cleaning Connection");
	}

	public void deleteObject(String objectId) {
		bindings.remove(objectId);
	}

	protected AtomicInteger getArgCounter() {
		return argCounter;
	}

	/**
	 * 
	 * @return The bindings of the Gateway. Should never be called by other
	 *         classes except subclasses and testing classes.
	 */
	public Map<String, Object> getBindings() {
		return bindings;
	}

	public CallbackClient getCallbackClient() {
		return cbClient;
	}

	public Object getEntryPoint() {
		return this.entryPoint;
	}

	public JVMView getDefaultJVMView() {
		return this.defaultJVMView;
	}

	protected String getNextObjectId() {
		return OBJECT_NAME_PREFIX + objCounter.getAndIncrement();
	}

	protected AtomicInteger getObjCounter() {
		return objCounter;
	}

	/**
	 * 
	 * @param objectId
	 * @return The object associated with the id or null if the object id is
	 *         unknown.
	 */
	public Object getObject(String objectId) {
		return bindings.get(objectId);
	}

	protected Object getObjectFromId(String targetObjectId) {
		if (targetObjectId.startsWith(Protocol.STATIC_PREFIX)) {
			return null;
		} else {
			return getObject(targetObjectId);
		}
	}

	public ReflectionEngine getReflectionEngine() {
		return rEngine;
	}

	@SuppressWarnings("rawtypes")
	public ReturnObject getReturnObject(Object object) {
		ReturnObject returnObject;
		if (object != null) {
			if (isPrimitiveObject(object)) {
				returnObject = ReturnObject.getPrimitiveReturnObject(object);
			} else if (object == ReflectionEngine.RETURN_VOID) {
				returnObject = ReturnObject.getVoidReturnObject();
			} else if (isList(object)) {
				String objectId = putNewObject(object);
				returnObject = ReturnObject.getListReturnObject(objectId,
						((List) object).size());
			} else if (isMap(object)) {
				String objectId = putNewObject(object);
				returnObject = ReturnObject.getMapReturnObject(objectId,
						((Map) object).size());
			} else if (isArray(object)) {
				String objectId = putNewObject(object);
				returnObject = ReturnObject.getArrayReturnObject(objectId,
						Array.getLength(object));
			} else if (isSet(object)) {
				String objectId = putNewObject(object);
				returnObject = ReturnObject.getSetReturnObject(objectId,
						((Set) object).size());
			} else if (isIterator(object)) {
				String objectId = putNewObject(object);
				returnObject = ReturnObject.getIteratorReturnObject(objectId);
			} else {
				String objectId = putNewObject(object);
				returnObject = ReturnObject.getReferenceReturnObject(objectId);
			}
		} else {
			returnObject = ReturnObject.getNullReturnObject();
		}
		return returnObject;
	}

	/**
	 * <p>
	 * Invokes a constructor and returned the constructed object.
	 * </p>
	 * 
	 * @param fqn
	 *            The fully qualified name of the class.
	 * @param args
	 * @return
	 */
	public ReturnObject invoke(String fqn, List<Object> args) {
		if (args == null) {
			args = new ArrayList<Object>();
		}
		ReturnObject returnObject = null;
		try {
			logger.info("Calling constructor: " + fqn);
			Object[] parameters = args.toArray();

			MethodInvoker method = rEngine.getConstructor(fqn, parameters);
			Object object = rEngine.invoke(null, method, parameters);
			returnObject = getReturnObject(object);

		} catch (Exception e) {
			throw new Py4JException(e);
		}

		return returnObject;
	}

	/**
	 * <p>
	 * Invokes a method.
	 * </p>
	 * 
	 * @param methodName
	 * @param targetObjectId
	 * @param args
	 * @return
	 */
	public ReturnObject invoke(String methodName, String targetObjectId,
			List<Object> args) {
		if (args == null) {
			args = new ArrayList<Object>();
		}
		ReturnObject returnObject = null;
		try {
			Object targetObject = getObjectFromId(targetObjectId);
			logger.info("Calling: " + methodName);
			Object[] parameters = args.toArray();

			MethodInvoker method = null;
			if (targetObject != null) {
				method = rEngine
						.getMethod(targetObject, methodName, parameters);
			} else {
				method = rEngine.getMethod(targetObjectId
						.substring(Protocol.STATIC_PREFIX.length()),
						methodName, parameters);
			}

			Object object = rEngine.invoke(targetObject, method, parameters);
			returnObject = getReturnObject(object);
		} catch (Exception e) {
			throw new Py4JException(e);
		}

		return returnObject;
	}

	protected boolean isArray(Object object) {
		return object.getClass().isArray();
	}

	protected boolean isList(Object object) {
		return object instanceof List;
	}

	protected boolean isMap(Object object) {
		return object instanceof Map;
	}

	protected boolean isPrimitiveObject(Object object) {
		return object instanceof Boolean || object instanceof String
				|| object instanceof Number || object instanceof Character;
	}

	protected boolean isSet(Object object) {
		return object instanceof Set;
	}

	private boolean isIterator(Object object) {
		return object instanceof Iterator;
	}

	public boolean isStarted() {
		return isStarted;
	}

	/**
	 * <p>
	 * Adds a new object to the gateway bindings and return the generated ID.
	 * Should NEVER be called by other classes except subclasses and testing
	 * classes.
	 * </p>
	 * 
	 * @param object
	 * @return
	 */
	public String putNewObject(Object object) {
		String id = getNextObjectId();
		bindings.put(id, object);
		return id;
	}

	public Object putObject(String id, Object object) {
		return bindings.put(id, object);
	}

	public void setStarted(boolean isStarted) {
		this.isStarted = isStarted;
	}

	/**
	 * <p>
	 * Releases all objects that were referenced by this Gateway.
	 * <p>
	 */
	public void shutdown() {
		isStarted = false;
		bindings.clear();
	}

	public void startup() {
		isStarted = true;
		if (entryPoint != null) {
			bindings.put(Protocol.ENTRY_POINT_OBJECT_ID, entryPoint);
		}
		bindings.put(Protocol.DEFAULT_JVM_OBJECT_ID, defaultJVMView);
	}

}
