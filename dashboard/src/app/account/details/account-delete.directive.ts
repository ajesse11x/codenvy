/*
 * Copyright (c) [2015] - [2017] Red Hat, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
'use strict';

/**
 * Defines a directive for displaying delete account widget.
 * @author Oleksii Orel
 */
export class AccountDelete {
  restrict = 'E';

  controller = 'AccountDeleteController';
  controllerAs = 'accountDeleteController';

  templateUrl = 'app/account/details/account-delete.html';

  scope = {};
}
