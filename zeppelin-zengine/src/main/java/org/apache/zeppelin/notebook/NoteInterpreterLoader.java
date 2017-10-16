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

package org.apache.zeppelin.notebook;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.apache.zeppelin.interpreter.Constants;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.Interpreter.RegisteredInterpreter;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterFactory;
import org.apache.zeppelin.interpreter.InterpreterGroup;
import org.apache.zeppelin.interpreter.InterpreterSetting;

/**
 * Interpreter loader per note.
 */
public class NoteInterpreterLoader {
  private transient InterpreterFactory factory;
  private static String SHARED_SESSION = "shared_session";
  String noteId;

  public NoteInterpreterLoader(InterpreterFactory factory) {
    this.factory = factory;
  }

  public void setNoteId(String noteId) {
    this.noteId = noteId;
  }

  /**
   * set interpreter ids
   * @param ids InterpreterSetting id list
   * @throws IOException
   */
  public void setInterpreters(List<String> ids) throws IOException {
    factory.putNoteInterpreterSettingBinding(noteId, ids);
  }

  public List<String> getInterpreters() {
    return factory.getNoteInterpreterSettingBinding(noteId);
  }

  public List<InterpreterSetting> getInterpreterSettings() {
    List<String> interpreterSettingIds = factory.getNoteInterpreterSettingBinding(noteId);
    LinkedList<InterpreterSetting> settings = new LinkedList<InterpreterSetting>();
    synchronized (interpreterSettingIds) {
      for (String id : interpreterSettingIds) {
        InterpreterSetting setting = factory.get(id);
        if (setting == null) {
          // interpreter setting is removed from factory. remove id from here, too
          interpreterSettingIds.remove(id);
        } else {
          settings.add(setting);
        }
      }
    }
    return settings;
  }

  private String getInterpreterInstanceKey(InterpreterSetting setting) {
    if (setting.getOption().isExistingProcess()) {
      return Constants.EXISTING_PROCESS;
    } else if (setting.getOption().isPerNoteSession() || setting.getOption().isPerNoteProcess()) {
      return noteId;
    } else {
      return SHARED_SESSION;
    }
  }

  private List<Interpreter> createOrGetInterpreterList(InterpreterSetting setting) {
    InterpreterGroup interpreterGroup =
        setting.getInterpreterGroup(noteId);
    synchronized (interpreterGroup) {
      String key = getInterpreterInstanceKey(setting);
      if (!interpreterGroup.containsKey(key)) {
        factory.createInterpretersForNote(setting, noteId, key);
      }
      return interpreterGroup.get(getInterpreterInstanceKey(setting));
    }
  }

  public void close() {
    // close interpreters in this note session
    List<InterpreterSetting> settings = this.getInterpreterSettings();
    if (settings == null || settings.size() == 0) {
      return;
    }

    System.err.println("close");
    for (InterpreterSetting setting : settings) {
      factory.removeInterpretersForNote(setting, noteId);
    }
  }

  public Interpreter get(String replName) {
    List<InterpreterSetting> settings = getInterpreterSettings();

    if (settings == null || settings.size() == 0) {
      return null;
    }

    if (replName == null || replName.trim().length() == 0) {
      // get default settings (first available)
      InterpreterSetting defaultSettings = settings.get(0);
      return createOrGetInterpreterList(defaultSettings).get(0);
    }

    if (Interpreter.registeredInterpreters == null) {
      return null;
    }

    Interpreter.RegisteredInterpreter interpreter = null;


    for(Interpreter.RegisteredInterpreter registeredInterpreter : Interpreter.registeredInterpreters.values())
    {
      if(registeredInterpreter.getName().equals(replName))
      {
        interpreter = registeredInterpreter;
        break;
      }
    }

    if (interpreter == null
            || interpreter.getClassName() == null) {
      throw new InterpreterException(replName + " interpreter not found");
    }

    String interpreterClassName = interpreter.getClassName();

    for (InterpreterSetting setting : settings) {
      if (interpreter.getGroup().equals(setting.getGroup())) {
        List<Interpreter> intpGroup = createOrGetInterpreterList(setting);
        for (Interpreter intp : intpGroup) {
          if (interpreterClassName.equals(intp.getClassName())) {
            return intp;
          }
        }
      }
    }
    throw new InterpreterException(replName + " interpreter not found");
  }
}
