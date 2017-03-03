/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.interpreter.remote;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Future;

import org.apache.thrift.TException;
import org.apache.zeppelin.display.AngularObject;
import org.apache.zeppelin.display.AngularObjectRegistry;
import org.apache.zeppelin.display.GUI;
import org.apache.zeppelin.display.Input;
import org.apache.zeppelin.interpreter.*;
import org.apache.zeppelin.interpreter.InterpreterResult.Type;
import org.apache.zeppelin.interpreter.thrift.InterpreterCompletion;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterContext;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterResult;
import org.apache.zeppelin.interpreter.thrift.RemoteInterpreterService.Client;
import org.apache.zeppelin.scheduler.Scheduler;
import org.apache.zeppelin.scheduler.SchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

/**
 * Proxy for Interpreter instance that runs on separate process
 */
public class RemoteInterpreter extends Interpreter {
  private final RemoteInterpreterProcessListener remoteInterpreterProcessListener;
  Logger logger = LoggerFactory.getLogger(RemoteInterpreter.class);
  Gson gson = new Gson();
  private String interpreterRunner;
  private String interpreterPath;
  private String localRepoPath;
  private String className;
  private String noteId;
  FormType formType;
  boolean initialized;
  private Map<String, String> env;
  private int connectTimeout;
  private int maxPoolSize;
  private TimeoutInfo timeoutInfo;
  private int pid = -1;
  private static String schedulerName;

  public RemoteInterpreter(Properties property,
      String noteId,
      String className,
      String interpreterRunner,
      String interpreterPath,
      String localRepoPath,
      int connectTimeout,
      int maxPoolSize,
      TimeoutInfo timeoutInfo,
      RemoteInterpreterProcessListener remoteInterpreterProcessListener) {
    super(property);
    this.noteId = noteId;
    this.className = className;
    initialized = false;
    this.interpreterRunner = interpreterRunner;
    this.interpreterPath = interpreterPath;
    this.localRepoPath = localRepoPath;
    env = getEnvFromInterpreterProperty(property);
    this.connectTimeout = connectTimeout;
    this.maxPoolSize = maxPoolSize;
    this.timeoutInfo = timeoutInfo;
    this.remoteInterpreterProcessListener = remoteInterpreterProcessListener;
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      @Override
      public void run() {
        RemoteInterpreter.this.close();
      }
    }));
  }

  public RemoteInterpreter(Properties property,
      String noteId,
      String className,
      String interpreterRunner,
      String interpreterPath,
      String localRepoPath,
      Map<String, String> env,
      int connectTimeout,
      RemoteInterpreterProcessListener remoteInterpreterProcessListener) {
    super(property);
    this.className = className;
    this.noteId = noteId;
    this.interpreterRunner = interpreterRunner;
    this.interpreterPath = interpreterPath;
    this.localRepoPath = localRepoPath;
    env.putAll(getEnvFromInterpreterProperty(property));
    this.env = env;
    this.connectTimeout = connectTimeout;
    this.maxPoolSize = 10;
    this.timeoutInfo = new TimeoutInfo();
    this.remoteInterpreterProcessListener = remoteInterpreterProcessListener;
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
      @Override
      public void run() {
        RemoteInterpreter.this.close();
      }
    }));
  }

  private Map<String, String> getEnvFromInterpreterProperty(Properties property) {
    Map<String, String> env = new HashMap<String, String>();
    for (Object key : property.keySet()) {
      if (isEnvString((String) key)) {
        env.put((String) key, property.getProperty((String) key));
      }
    }
    return env;
  }

  static boolean isEnvString(String key) {
    if (key == null || key.length() == 0) {
      return false;
    }

    return key.matches("^[A-Z_0-9]*");
  }

  @Override
  public String getClassName() {
    return className;
  }

  public RemoteInterpreterProcess getInterpreterProcess() {
    InterpreterGroup intpGroup = getInterpreterGroup();
    if (intpGroup == null) {
      return null;
    }

    synchronized (intpGroup) {
      if (intpGroup.getRemoteInterpreterProcess() == null) {
        // create new remote process
        RemoteInterpreterProcess remoteProcess = new RemoteInterpreterProcess(
            interpreterRunner, interpreterPath, localRepoPath, env, connectTimeout,
            remoteInterpreterProcessListener);

        intpGroup.setRemoteInterpreterProcess(remoteProcess);
      }

      return intpGroup.getRemoteInterpreterProcess();
    }
  }

  public synchronized void init() {
    if (initialized == true) {
      return;
    }

    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();

    final InterpreterGroup interpreterGroup = getInterpreterGroup();
    interpreterProcess.reference(interpreterGroup);
    interpreterProcess.setMaxPoolSize(
        Math.max(this.maxPoolSize, interpreterProcess.getMaxPoolSize()));
    String groupId = interpreterGroup.getId();

    synchronized (interpreterProcess) {
      Client client = null;
      try {
        client = interpreterProcess.getClient();
      } catch (Exception e1) {
        throw new InterpreterException(e1);
      }

      boolean broken = false;
      try {
        logger.info("Create remote interpreter {}", getClassName());
        client.createInterpreter(groupId, noteId,
          getClassName(), (Map) property);

        // Push angular object loaded from JSON file to remote interpreter
        if (!interpreterGroup.isAngularRegistryPushed()) {
          pushAngularObjectRegistryToRemote(client);
          interpreterGroup.setAngularRegistryPushed(true);
        }

      } catch (TException e) {
        logger.error("Failed to create interpreter: {}", getClassName());
        throw new InterpreterException(e);
      } finally {
        // TODO(jongyoul): Fixed it when not all of interpreter in same interpreter group are broken
        interpreterProcess.releaseClient(client, broken);
      }
    }
    initialized = true;
  }



  @Override
  public void open() {
    InterpreterGroup interpreterGroup = getInterpreterGroup();

    synchronized (interpreterGroup) {
      // initialize all interpreters in this interpreter group
      List<Interpreter> interpreters = interpreterGroup.get(noteId);
      for (Interpreter intp : new ArrayList<>(interpreters)) {
        Interpreter p = intp;
        while (p instanceof WrappedInterpreter) {
          p = ((WrappedInterpreter) p).getInnerInterpreter();
        }
        try {
          ((RemoteInterpreter) p).init();
          RemoteInterpreterProcess process = getInterpreterProcess();
          try {
            Client client = process.getClient();
            this.pid = client.getPid();
          } catch (Exception e) {
            pid = -1;
          }
        } catch (InterpreterException e) {
          logger.error("Failed to initialize interpreter: {}. Remove it from interpreterGroup",
              p.getClassName());
          interpreters.remove(p);
        }
      }
    }
  }

  @Override
  public void close() {
    TimeoutTaskService.doTimeoutTask(new CloserTask(), timeoutInfo.getCloseTimeout(),
                                     (Future<?> future, Exception e) -> {
      if (future != null && !future.isDone()) {
        future.cancel(true);
      }
      logger.info("Failed to close interpreter normally, moving to force-kill");
      forceKill(pid);
    });
  }

  private void forceKill(int pid) {
    if (pid == -1){
      throw new RuntimeException("Failed to identify the pid of interpreter process");
    }
    try {
      Runtime.getRuntime().exec("kill " + pid);
      logger.info("Issuing kill command for process " + pid);
      sleep(timeoutInfo.getWaitBetweenKills());
      if (isProcessTerminated(pid)) {
        return;
      }
      logger.info("Kill command failed. Issuing kill -2 command for process " + pid);
      Runtime.getRuntime().exec("kill -2 " + pid);
      sleep(timeoutInfo.getWaitBetweenKills());
      if (isProcessTerminated(pid)) {
        return;
      }
      logger.warn("Kill -2 command failed. Issuing kill -9 command for process " + pid);
      Runtime.getRuntime().exec("kill -9 " + pid);
    } catch (Exception e) {
      throw new RuntimeException("Failed to force-kill the interpreter");
    }
  }

  private void sleep(int ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      // do nothing
    }
  }

  private boolean isProcessTerminated(int pid) {
    try {
      int status = Runtime.getRuntime().exec("kill -0 " + pid).waitFor();
      return status == 0;
    } catch (IOException | InterruptedException e) {
      throw new RuntimeException("Failed to force-kill the interpreter");
    }
  }

  private class CloserTask implements Runnable {

    @Override
    public void run() {
      RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();

      Client client = null;
      boolean broken = false;
      try {
        client = interpreterProcess.getClient();
        if (client != null) {
          client.close(noteId, className);
        }
      } catch (TException e) {
        broken = true;
        throw new InterpreterException(e);
      } catch (Exception e1) {
        throw new InterpreterException(e1);
      }
      finally {
        if (client != null) {
          interpreterProcess.releaseClient(client, broken);
        }
        getInterpreterProcess().dereference();
      }
    }
  }

  @Override
  public InterpreterResult interpret(String st, InterpreterContext context) {
    logger.debug("st: {}", st);
    FormType form = getFormType();
    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();
    final Client client;
    try {
      client = interpreterProcess.getClient();
    } catch (Exception e1) {
      throw new InterpreterException(e1);
    }

    InterpreterContextRunnerPool interpreterContextRunnerPool = interpreterProcess
        .getInterpreterContextRunnerPool();

    List<InterpreterContextRunner> runners = context.getRunners();
    if (runners != null && runners.size() != 0) {
      // assume all runners in this InterpreterContext have the same note id
      String noteId = runners.get(0).getNoteId();

      interpreterContextRunnerPool.clear(noteId);
      interpreterContextRunnerPool.addAll(noteId, runners);
    }

    return TimeoutTaskService.pollingTimeoutTask(
            () -> doInterpret(client, st, context, interpreterProcess, form),
            () -> getProgress(context) >= 0,
            timeoutInfo.getGeneralTimeout(),
            (Future<InterpreterResult> future, Exception e) ->
            {
              future.cancel(true);
              return new InterpreterResult(InterpreterResult.Code.ERROR,
                                           Type.TEXT, e.getMessage());
            });
  }

  private InterpreterResult doInterpret(Client client,
                                        String st,
                                        InterpreterContext context,
                                        RemoteInterpreterProcess interpreterProcess,
                                        FormType form)
  {
    boolean broken = false;
    try {

      final GUI currentGUI = context.getGui();
      RemoteInterpreterResult remoteResult = client.interpret(noteId, className,
                                                              st, convert(context));
      Map<String, Object> remoteConfig = (Map<String, Object>) gson.fromJson(
              remoteResult.getConfig(), new TypeToken<Map<String, Object>>() {
              }.getType());
      context.getConfig().clear();
      context.getConfig().putAll(remoteConfig);


      if (form == FormType.NATIVE) {
        GUI remoteGui = gson.fromJson(remoteResult.getGui(), GUI.class);
        currentGUI.clear();
        currentGUI.setParams(remoteGui.getParams());
        currentGUI.setForms(remoteGui.getForms());
      } else if (form == FormType.SIMPLE) {
        final Map<String, Input> currentForms = currentGUI.getForms();
        final Map<String, Object> currentParams = currentGUI.getParams();
        final GUI remoteGUI = gson.fromJson(remoteResult.getGui(), GUI.class);
        final Map<String, Input> remoteForms = remoteGUI.getForms();
        final Map<String, Object> remoteParams = remoteGUI.getParams();
        currentForms.putAll(remoteForms);
        currentParams.putAll(remoteParams);
      }

      InterpreterResult result = convert(remoteResult);
      return result;
    } catch (TException e) {
      broken = true;
      throw new InterpreterException(e);
    } finally {
      interpreterProcess.releaseClient(client, broken);
    }
  }

  @Override
  public void cancel(InterpreterContext context) {
    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();
    final Client client;
    try {
      client = interpreterProcess.getClient();
    } catch (Exception e1) {
      throw new InterpreterException(e1);
    }
    TimeoutTaskService.doTimeoutTask(
            () -> doCancel(client, context, interpreterProcess),
            timeoutInfo.getGeneralTimeout(),
            (Future<?> future, Exception e) -> {
              logger.error("Call to cancel timed out. Please restart the interpreter");
            });
  }

  private void doCancel(Client client, InterpreterContext context, RemoteInterpreterProcess interpreterProcess)
  {
    boolean broken = false;
    try {
      client.cancel(noteId, className, convert(context));
    } catch (TException e) {
      broken = true;
      throw new InterpreterException(e);
    } finally {
      interpreterProcess.releaseClient(client, broken);
    }
  }


  @Override
  public FormType getFormType() {
    init();

    if (formType != null) {
      return formType;
    }

    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();
    Client client;
    try {
      client = interpreterProcess.getClient();
    } catch (Exception e1) {
      throw new InterpreterException(e1);
    }
    return TimeoutTaskService.doTimeoutTask(
            () -> doGetFormType(client, interpreterProcess),
            timeoutInfo.getGeneralTimeout(),
            (Future<FormType> future, Exception e) -> {
              logger.error("Call to getFormType timed out. Please restart the interpreter");
              return null;
            });
  }

  private FormType doGetFormType(final Client client, RemoteInterpreterProcess interpreterProcess)
  {
    boolean broken = false;
    try {
      return FormType.valueOf(client.getFormType(noteId, className));
    } catch (TException e) {
      broken = true;
      throw new InterpreterException(e);
    } finally {
      interpreterProcess.releaseClient(client, broken);
    }
  }

  @Override
  public int getProgress(InterpreterContext context) {
    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();
    if (interpreterProcess == null || !interpreterProcess.isRunning()) {
      return 0;
    }

    final Client client;
    try {
      client = interpreterProcess.getClient();
    } catch (Exception e1) {
      throw new InterpreterException(e1);
    }
    return TimeoutTaskService.doTimeoutTask(
            () -> this.doGetProgress(client, interpreterProcess, context),
            timeoutInfo.getGeneralTimeout(),
            (Future<Integer> future, Exception e) -> {
              logger.error("Call to getProgress timed out. Please restart the interpreter.");
              return -1;
            });
  }

  private int doGetProgress(Client client, RemoteInterpreterProcess interpreterProcess,
                            InterpreterContext context)
  {
    boolean broken = false;
    try {
      return client.getProgress(noteId, className, convert(context));
    } catch (TException e) {
      broken = true;
      throw new InterpreterException(e);
    } finally {
      interpreterProcess.releaseClient(client, broken);
    }
  }


  @Override
  public List<InterpreterCompletion> completion(String buf, int cursor) {


    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();
    final Client client;
    try {
      client = interpreterProcess.getClient();
    } catch (Exception e1) {
      throw new InterpreterException(e1);
    }
    return TimeoutTaskService.doTimeoutTask(
            () -> this.doCompletion(client, interpreterProcess, buf, cursor),
            timeoutInfo.getGeneralTimeout(),
            (Future<List<InterpreterCompletion>> future, Exception e) -> {
              logger.error("Call to completion timed out. Please restart the interpreter.");
              return Collections.emptyList();
            });
  }


  private List<InterpreterCompletion> doCompletion(Client client,
                                                   RemoteInterpreterProcess interpreterProcess,
                                                   String buf, int cursor) {
    boolean broken = false;
    try {
      List completion = client.completion(noteId, className, buf, cursor);
      return completion;
    } catch (TException e) {
      broken = true;
      throw new InterpreterException(e);
    } finally {
      interpreterProcess.releaseClient(client, broken);
    }
  }

  @Override
  public Scheduler getScheduler() {
    int maxConcurrency = maxPoolSize;
    RemoteInterpreterProcess interpreterProcess = getInterpreterProcess();
    if (interpreterProcess == null) {
      return null;
    } else {
      return SchedulerFactory.singleton().createOrGetRemoteScheduler(
          RemoteInterpreter.class.getName() + noteId + interpreterProcess.hashCode(),
          noteId,
          interpreterProcess,
          maxConcurrency);
    }
  }

  private String getInterpreterGroupKey(InterpreterGroup interpreterGroup) {
    return interpreterGroup.getId();
  }

  private RemoteInterpreterContext convert(InterpreterContext ic) {
    return new RemoteInterpreterContext(
        ic.getNoteId(),
        ic.getParagraphId(),
        ic.getParagraphTitle(),
        ic.getParagraphText(),
        gson.toJson(ic.getAuthenticationInfo()),
        gson.toJson(ic.getConfig()),
        gson.toJson(ic.getGui()),
        gson.toJson(ic.getRunners()));
  }

  private InterpreterResult convert(RemoteInterpreterResult result) {
    return new InterpreterResult(
        InterpreterResult.Code.valueOf(result.getCode()),
        Type.valueOf(result.getType()),
        result.getMsg());
  }

  /**
   * Push local angular object registry to
   * remote interpreter. This method should be
   * call ONLY inside the init() method
   * @param client
   * @throws TException
   */
  void pushAngularObjectRegistryToRemote(Client client) throws TException {
    final AngularObjectRegistry angularObjectRegistry = this.getInterpreterGroup()
            .getAngularObjectRegistry();

    if (angularObjectRegistry != null && angularObjectRegistry.getRegistry() != null) {
      final Map<String, Map<String, AngularObject>> registry = angularObjectRegistry
              .getRegistry();

      logger.info("Push local angular object registry from ZeppelinServer to" +
              " remote interpreter group {}", this.getInterpreterGroup().getId());

      final java.lang.reflect.Type registryType = new TypeToken<Map<String,
              Map<String, AngularObject>>>() {}.getType();

      Gson gson = new Gson();
      client.angularRegistryPush(gson.toJson(registry, registryType));
    }
  }
}
