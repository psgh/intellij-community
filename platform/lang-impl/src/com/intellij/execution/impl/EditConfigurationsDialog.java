/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.execution.impl;

import com.intellij.execution.ExecutionBundle;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ex.SingleConfigurableEditor;
import com.intellij.openapi.project.Project;

public class EditConfigurationsDialog extends SingleConfigurableEditor {
  private Configurable mySelectedConfigurable;

  public EditConfigurationsDialog(final Project project) {
    super(project, new RunConfigurable(project));
    setTitle(ExecutionBundle.message("run.debug.dialog.title"));
  }

  @Override
  protected void doOKAction() {
    final Configurable configurable = getConfigurable();
    mySelectedConfigurable = ((RunConfigurable)configurable).getSelectedConfigurable();

    super.doOKAction();
  }

  public Configurable getSelectedConfigurable() {
    return mySelectedConfigurable;
  }

  protected String getDimensionServiceKey() {
    return "#com.intellij.execution.impl.EditConfigurationsDialog";
  }
}