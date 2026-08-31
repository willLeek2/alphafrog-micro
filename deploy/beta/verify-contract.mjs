#!/usr/bin/env node

import {execFileSync} from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const manifestSchema = path.join(here, 'manifest.schema.json');
const stateSchema = path.join(here, 'controller-state.schema.json');
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'alphafrog-beta-contract-'));
const clone = value => structuredClone(value);
const repeated = character => character.repeat(64);
let schemaChecks = 0;
let contractChecks = 0;

function canonicalize(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalize).join(',')}]`;
  return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalize(value[key])}`).join(',')}}`;
}

function digest(value) {
  return crypto.createHash('sha256').update(canonicalize(value), 'utf8').digest('hex');
}

function serviceDigest(service) {
  const input = clone(service);
  delete input.serviceSpecSha256;
  return digest(input);
}

function assert(condition, message) {
  contractChecks++;
  if (!condition) throw new Error(message);
}

function runAjv(command, schema, data, shouldPass) {
  const args = [
    '--yes',
    '--package=ajv-cli@5.0.0',
    '--package=ajv-formats@3.0.1',
    'ajv',
    command,
    '--spec=draft2020',
    '--strict=true',
    '-c',
    'ajv-formats',
    '-s',
    schema
  ];
  if (data) args.push('-d', data);
  let passed = true;
  let failureOutput = '';
  try {
    execFileSync('npx', args, {stdio: 'pipe'});
  } catch (error) {
    passed = false;
    failureOutput = `${error.stdout ?? ''}\n${error.stderr ?? ''}`;
  }
  schemaChecks++;
  if (passed !== shouldPass) {
    throw new Error(`${path.basename(data ?? schema)} ${shouldPass ? 'should pass' : 'should fail'} AJV`);
  }
  if (!shouldPass && !passed && !/\binvalid\b/i.test(failureOutput)) {
    throw new Error(`${path.basename(data)} AJV did not report a validation failure: ${failureOutput.trim()}`);
  }
}

function writeFixture(name, value) {
  const target = path.join(tempDir, `${name}.json`);
  fs.writeFileSync(target, `${JSON.stringify(value, null, 2)}\n`);
  return target;
}

const manifest = {
  schemaVersion: 1,
  deploymentId: 'beta-main-001',
  trafficScopeId: 'main-beta',
  manifestVersion: 2,
  gitCommit: '1'.repeat(40),
  owner: {ownerId: 'frog'},
  createdAt: '2026-09-01T00:00:00Z',
  expiresAt: '2026-09-08T00:00:00Z',
  services: [{
    serviceName: 'agent-service',
    releaseId: 'release-2',
    serviceSpecSha256: repeated('a'),
    machineId: 'beta-machine-1',
    image: {
      repositoryDigest: `registry.local/agent-service@sha256:${repeated('b')}`,
      localImageId: `sha256:${repeated('c')}`
    },
    runtime: {
      containerPort: 18080,
      hostPorts: [28080, 28081],
      healthCheckProfile: 'CONTROLLER_TCP_V1',
      readinessTimeoutSeconds: 120,
      routeLeaseSeconds: 30,
      shutdownProfile: 'SPRING_BOOT_HTTP_DUBBO_V1',
      applicationDrainSeconds: 55,
      drainGraceSeconds: 60
    },
    registration: {
      serviceName: 'com.alphafrog.AgentService:1.0@@providers',
      groupName: 'DEFAULT_GROUP',
      namespaceId: 'public',
      clusterName: 'DEFAULT'
    },
    runtimeConfigSha256: null
  }]
};
manifest.services[0].serviceSpecSha256 = serviceDigest(manifest.services[0]);
const targetSpec = manifest.services[0].serviceSpecSha256;
const manifestSha256 = digest(manifest);

function registration(instanceId, releaseId, port, selectable) {
  return {
    serviceName: 'com.alphafrog.AgentService:1.0@@providers',
    groupName: 'DEFAULT_GROUP',
    namespaceId: 'public',
    clusterName: 'DEFAULT',
    ip: '10.0.0.8',
    port,
    nacosInstanceId: `nacos:${instanceId}`,
    enabled: selectable,
    healthy: true,
    weight: selectable ? 1 : 0,
    ephemeral: true,
    metadata: {
      'alphafrog.traffic-scope-id': 'main-beta',
      'alphafrog.release-id': releaseId,
      'alphafrog.instance-id': instanceId
    }
  };
}

function baseInstance(instanceId, releaseId, version, spec, slot, port, selectable = true) {
  return {
    instanceId,
    machineId: 'beta-machine-1',
    releaseId,
    manifestVersion: version,
    serviceSpecSha256: spec,
    containerName: `af-${instanceId}`,
    containerId: (slot === 'A' ? 'a' : 'b').repeat(64),
    portSlot: slot,
    hostPort: port,
    endpoint: {address: '10.0.0.8', port},
    registration: registration(instanceId, releaseId, port, selectable)
  };
}

const oldActive = baseInstance('instance-old', 'release-1', 1, repeated('d'), 'A', 28080);
const newActive = baseInstance('instance-new', 'release-2', 2, targetSpec, 'B', 28081);
const newDisabled = baseInstance('instance-new', 'release-2', 2, targetSpec, 'B', 28081, false);
const candidateStarting = {...newDisabled, readiness: 'STARTING', readinessObservedAt: null, readinessDeadline: '2026-09-01T00:02:00Z'};
const candidateReady = {...newDisabled, readiness: 'READY', readinessObservedAt: '2026-09-01T00:01:00Z', readinessDeadline: '2026-09-01T00:02:00Z'};
const oldWaiting = {...oldActive, drainStartedAt: null, drainDeadline: null};
const oldDisabled = {...clone(oldActive), drainStartedAt: '2026-09-01T00:02:30Z', drainDeadline: '2026-09-01T00:03:30Z'};
oldDisabled.registration.enabled = false;
oldDisabled.registration.weight = 0;

const routeOld = {
  defaultInstanceId: 'instance-old',
  defaultReleaseId: 'release-1',
  routeVersion: 7,
  updatedAt: '2026-09-01T00:00:00Z',
  previousVersionValidUntil: null
};
const routeNewStable = {...routeOld, defaultInstanceId: 'instance-new', defaultReleaseId: 'release-2', routeVersion: 8, updatedAt: '2026-09-01T00:02:00Z'};
const routeNewWaiting = {...routeNewStable, previousVersionValidUntil: '2026-09-01T00:02:30Z'};
const routeNone = {...routeOld, defaultInstanceId: null, defaultReleaseId: null, routeVersion: 0};
const routeNoneWaiting = {...routeNone, routeVersion: 9, updatedAt: '2026-09-01T00:02:00Z', previousVersionValidUntil: '2026-09-01T00:02:30Z'};

function operation(type, phase, candidateInstanceId) {
  return {operationId: `op-${type.toLowerCase()}-${phase.toLowerCase()}`, type, phase, candidateInstanceId, startedAt: '2026-09-01T00:00:00Z'};
}

function error(failedOperationType, recoveryClass) {
  return {
    code: 'DEPLOYMENT_STEP_FAILED',
    message: 'The controller stopped at a verified boundary',
    at: '2026-09-01T00:02:00Z',
    failedOperationType,
    recoveryClass
  };
}

function service(overrides = {}) {
  return {
    serviceName: 'agent-service',
    phase: 'STABLE',
    targetManifestVersion: 2,
    targetServiceSpecSha256: targetSpec,
    activeInstance: newActive,
    candidateInstance: null,
    drainingInstance: null,
    route: routeNewStable,
    operation: null,
    failedManifestVersion: null,
    lastError: null,
    ...overrides
  };
}

function state(serviceState, deploymentOverrides = {}) {
  return {
    schemaVersion: 1,
    stateVersion: 12,
    updatedAt: '2026-09-01T00:02:00Z',
    deployments: [{
      deploymentId: 'beta-main-001',
      trafficScopeId: 'main-beta',
      phase: 'ACTIVE',
      acceptedManifestVersion: 2,
      manifestSha256,
      gitCommit: '1'.repeat(40),
      owner: {ownerId: 'frog'},
      expiresAt: '2026-09-08T00:00:00Z',
      services: [serviceState],
      ...deploymentOverrides
    }]
  };
}

const positiveStates = {
  stable: state(service()),
  createWaiting: state(service({
    phase: 'CREATING', activeInstance: null, candidateInstance: candidateStarting, route: routeNone,
    operation: operation('CREATE', 'WAITING_CANDIDATE_READINESS', 'instance-new')
  })),
  updateSwitching: state(service({
    phase: 'UPDATING', activeInstance: oldActive, candidateInstance: candidateReady, route: routeOld,
    operation: operation('UPDATE', 'SWITCHING_TRAFFIC', 'instance-new')
  })),
  updateLeaseWait: state(service({
    phase: 'UPDATING', activeInstance: newActive, drainingInstance: oldWaiting, route: routeNewWaiting,
    operation: operation('UPDATE', 'WAITING_OLD_ROUTE_LEASES', null)
  })),
  updateDraining: state(service({
    phase: 'UPDATING', activeInstance: newActive, drainingInstance: oldDisabled, route: routeNewWaiting,
    operation: operation('UPDATE', 'DRAINING_PREVIOUS', null)
  })),
  deleteRemoving: state(service({
    phase: 'DELETING', operation: operation('DELETE', 'REMOVING_TRAFFIC', null)
  }), {phase: 'DELETING'}),
  deleteLeaseWait: state(service({
    phase: 'DELETING', activeInstance: null, drainingInstance: {...newActive, drainStartedAt: null, drainDeadline: null},
    route: routeNoneWaiting, operation: operation('DELETE', 'WAITING_DELETE_ROUTE_LEASES', null)
  }), {phase: 'DELETING'}),
  failedCreate: state(service({
    phase: 'FAILED', activeInstance: null, route: routeNone, failedManifestVersion: 2,
    lastError: error('CREATE', 'CLEAN_RETRYABLE')
  }))
};
positiveStates.stableIpv6 = clone(positiveStates.stable);
positiveStates.stableIpv6.deployments[0].services[0].activeInstance.registration.ip = '2001:db8::1';

function customErrors(manifestValue, stateValue) {
  const errors = [];
  const specByName = new Map();
  const reservedPorts = new Set();
  if (Date.parse(manifestValue.expiresAt) <= Date.parse(manifestValue.createdAt)) errors.push('manifest expiry');
  for (const spec of manifestValue.services) {
    if (specByName.has(spec.serviceName)) errors.push(`duplicate manifest service ${spec.serviceName}`);
    specByName.set(spec.serviceName, spec);
    if (spec.serviceSpecSha256 !== serviceDigest(spec)) errors.push(`service digest ${spec.serviceName}`);
    if (spec.runtime.applicationDrainSeconds + 5 > spec.runtime.drainGraceSeconds) errors.push(`drain reserve ${spec.serviceName}`);
    if (spec.registration.namespaceId !== 'public' && spec.registration.namespaceId.length === 0) errors.push(`namespace ${spec.serviceName}`);
    for (const port of spec.runtime.hostPorts) {
      const reservation = `${spec.machineId}:${port}`;
      if (reservedPorts.has(reservation)) errors.push(`duplicate reserved port ${reservation}`);
      reservedPorts.add(reservation);
    }
  }
  let operationCount = 0;
  const deploymentIds = new Set();
  const trafficScopes = new Set();
  const instanceIds = new Set();
  for (const deployment of stateValue.deployments) {
    if (deploymentIds.has(deployment.deploymentId)) errors.push(`duplicate deployment ${deployment.deploymentId}`);
    if (trafficScopes.has(deployment.trafficScopeId)) errors.push(`duplicate traffic scope ${deployment.trafficScopeId}`);
    deploymentIds.add(deployment.deploymentId);
    trafficScopes.add(deployment.trafficScopeId);
    if (deployment.manifestSha256 !== digest(manifestValue)) errors.push('manifest digest');
    const stateServiceNames = new Set();
    for (const item of deployment.services) {
      if (stateServiceNames.has(item.serviceName)) errors.push(`duplicate state service ${item.serviceName}`);
      stateServiceNames.add(item.serviceName);
      if (item.operation) operationCount++;
      const spec = specByName.get(item.serviceName);
      const roleSlots = new Set();
      for (const instance of [item.activeInstance, item.candidateInstance, item.drainingInstance].filter(Boolean)) {
        if (instanceIds.has(instance.instanceId)) errors.push(`duplicate instance ${instance.instanceId}`);
        instanceIds.add(instance.instanceId);
        if (roleSlots.has(instance.portSlot)) errors.push(`duplicate role slot ${item.serviceName}:${instance.portSlot}`);
        roleSlots.add(instance.portSlot);
        const registrationValue = instance.registration;
        if (registrationValue.metadata['alphafrog.traffic-scope-id'] !== deployment.trafficScopeId) errors.push(`scope metadata ${instance.instanceId}`);
        if (registrationValue.metadata['alphafrog.release-id'] !== instance.releaseId) errors.push(`release metadata ${instance.instanceId}`);
        if (registrationValue.metadata['alphafrog.instance-id'] !== instance.instanceId) errors.push(`instance metadata ${instance.instanceId}`);
        if (registrationValue.port !== instance.hostPort || instance.endpoint.port !== instance.hostPort) errors.push(`endpoint ${instance.instanceId}`);
        if (spec && !spec.runtime.hostPorts.includes(instance.hostPort)) errors.push(`port slot ${instance.instanceId}`);
      }
      if (item.activeInstance && (!item.activeInstance.registration.enabled || item.activeInstance.registration.weight !== 1 || !item.activeInstance.registration.healthy)) errors.push(`active not selectable ${item.serviceName}`);
      if (item.candidateInstance && (item.candidateInstance.registration.enabled || item.candidateInstance.registration.weight !== 0)) errors.push(`candidate selectable ${item.serviceName}`);
      if (item.operation?.phase === 'SWITCHING_TRAFFIC' && (!item.candidateInstance?.registration.healthy || item.candidateInstance?.readiness !== 'READY')) errors.push(`candidate not ready ${item.serviceName}`);
      if (item.operation?.phase === 'WAITING_OLD_ROUTE_LEASES' && (!item.drainingInstance?.registration.enabled || item.drainingInstance?.registration.weight !== 1)) errors.push(`old disabled before lease ${item.serviceName}`);
      if (item.operation?.phase === 'WAITING_DELETE_ROUTE_LEASES' && (!item.drainingInstance?.registration.enabled || item.drainingInstance?.registration.weight !== 1)) errors.push(`delete old disabled before lease ${item.serviceName}`);
      if (['DRAINING_PREVIOUS', 'DRAINING_ACTIVE'].includes(item.operation?.phase) && (item.drainingInstance?.registration.enabled || item.drainingInstance?.registration.weight !== 0)) errors.push(`old selectable while draining ${item.serviceName}`);
      if (item.route.previousVersionValidUntil) {
        const expected = Date.parse(item.route.updatedAt) + spec.runtime.routeLeaseSeconds * 1000;
        if (Date.parse(item.route.previousVersionValidUntil) !== expected) errors.push(`route lease ${item.serviceName}`);
      }
      if (item.lastError?.recoveryClass === 'DELETE_RETRYABLE' && item.lastError.failedOperationType !== 'DELETE') errors.push(`delete recovery class ${item.serviceName}`);
      if (item.lastError?.recoveryClass === 'CLEAN_RETRYABLE' && item.lastError.failedOperationType === 'DELETE') errors.push(`clean recovery class ${item.serviceName}`);
      if (item.phase === 'STABLE' && item.activeInstance && (item.route.defaultInstanceId !== item.activeInstance.instanceId || item.route.defaultReleaseId !== item.activeInstance.releaseId)) errors.push(`stable route ${item.serviceName}`);
    }
  }
  if (operationCount > 1) errors.push('global operation count');
  return errors;
}

try {
  runAjv('compile', manifestSchema, null, true);
  runAjv('compile', stateSchema, null, true);
  runAjv('validate', manifestSchema, writeFixture('manifest-valid', manifest), true);
  for (const [name, value] of Object.entries(positiveStates)) {
    runAjv('validate', stateSchema, writeFixture(`state-${name}`, value), true);
    const errors = customErrors(manifest, value);
    assert(errors.length === 0, `${name} custom checks should pass: ${errors.join(', ')}`);
  }

  const invalidFixtures = [];
  const badTime = clone(manifest);
  badTime.createdAt = '2026-99-99T99:99:99Z';
  invalidFixtures.push(['manifest-invalid-time', manifestSchema, badTime]);
  const badIpv4 = clone(positiveStates.stable);
  badIpv4.deployments[0].services[0].activeInstance.registration.ip = 'not-an-ip';
  invalidFixtures.push(['state-invalid-ip', stateSchema, badIpv4]);
  const missingNacosFact = clone(positiveStates.stable);
  delete missingNacosFact.deployments[0].services[0].activeInstance.registration.enabled;
  invalidFixtures.push(['state-missing-nacos-fact', stateSchema, missingNacosFact]);
  const illegalPhase = clone(positiveStates.updateSwitching);
  illegalPhase.deployments[0].services[0].operation.type = 'CREATE';
  invalidFixtures.push(['state-illegal-operation-phase', stateSchema, illegalPhase]);
  const incompleteError = clone(positiveStates.failedCreate);
  delete incompleteError.deployments[0].services[0].lastError.recoveryClass;
  invalidFixtures.push(['state-incomplete-error', stateSchema, incompleteError]);
  const reservedDeployment = clone(manifest);
  reservedDeployment.deploymentId = 'stable';
  invalidFixtures.push(['manifest-reserved-deployment', manifestSchema, reservedDeployment]);
  const sameHostPorts = clone(manifest);
  sameHostPorts.services[0].runtime.hostPorts = [28080, 28080];
  invalidFixtures.push(['manifest-same-host-ports', manifestSchema, sameHostPorts]);
  const mutableImage = clone(manifest);
  mutableImage.services[0].image.repositoryDigest = 'registry.local/agent-service:latest';
  invalidFixtures.push(['manifest-mutable-image', manifestSchema, mutableImage]);
  for (const [name, schema, value] of invalidFixtures) {
    runAjv('validate', schema, writeFixture(name, value), false);
  }

  const customNegatives = [];
  const shortDockerTimeout = clone(manifest);
  shortDockerTimeout.services[0].runtime.drainGraceSeconds = 58;
  shortDockerTimeout.services[0].serviceSpecSha256 = serviceDigest(shortDockerTimeout.services[0]);
  customNegatives.push(['drain reserve', shortDockerTimeout, positiveStates.stable]);
  const selectableCandidate = clone(positiveStates.updateSwitching);
  selectableCandidate.deployments[0].services[0].candidateInstance.registration.enabled = true;
  selectableCandidate.deployments[0].services[0].candidateInstance.registration.weight = 1;
  customNegatives.push(['candidate selectable', manifest, selectableCandidate]);
  const earlyDisable = clone(positiveStates.updateLeaseWait);
  earlyDisable.deployments[0].services[0].drainingInstance.registration.enabled = false;
  earlyDisable.deployments[0].services[0].drainingInstance.registration.weight = 0;
  customNegatives.push(['old disabled before lease', manifest, earlyDisable]);
  const badMetadata = clone(positiveStates.stable);
  badMetadata.deployments[0].services[0].activeInstance.registration.metadata['alphafrog.release-id'] = 'wrong-release';
  customNegatives.push(['registration metadata', manifest, badMetadata]);
  const badLease = clone(positiveStates.updateLeaseWait);
  badLease.deployments[0].services[0].route.previousVersionValidUntil = '2026-09-01T00:02:31Z';
  customNegatives.push(['route lease', manifest, badLease]);
  const badServiceDigest = clone(manifest);
  badServiceDigest.services[0].serviceSpecSha256 = repeated('f');
  customNegatives.push(['service digest', badServiceDigest, positiveStates.stable]);
  const duplicateServiceManifest = clone(manifest);
  duplicateServiceManifest.services.push(clone(duplicateServiceManifest.services[0]));
  customNegatives.push(['duplicate service', duplicateServiceManifest, positiveStates.stable]);
  const duplicateSlot = clone(positiveStates.updateSwitching);
  duplicateSlot.deployments[0].services[0].candidateInstance.portSlot = 'A';
  customNegatives.push(['duplicate role slot', manifest, duplicateSlot]);
  const stableRouteMismatch = clone(positiveStates.stable);
  stableRouteMismatch.deployments[0].services[0].route.defaultInstanceId = 'instance-old';
  customNegatives.push(['stable route mismatch', manifest, stableRouteMismatch]);
  const twoOperations = clone(positiveStates.updateSwitching);
  const secondOperatingService = clone(twoOperations.deployments[0].services[0]);
  secondOperatingService.serviceName = 'agent-tools-service';
  twoOperations.deployments[0].services.push(secondOperatingService);
  customNegatives.push(['global operation count', manifest, twoOperations]);
  for (const [name, manifestValue, stateValue] of customNegatives) {
    assert(customErrors(manifestValue, stateValue).length > 0, `${name} should fail custom checks`);
  }

  const compact = JSON.stringify(manifest);
  const pretty = JSON.stringify(manifest, null, 2);
  assert(digest(JSON.parse(compact)) === digest(JSON.parse(pretty)), 'manifest formatting must not change digest');
  assert(digest(JSON.parse(pretty)) === manifestSha256, 'manifest digest vector must stay fixed');

  const createQueue = clone(positiveStates.failedCreate);
  const queuedCreate = clone(createQueue.deployments[0].services[0]);
  queuedCreate.serviceName = 'agent-tools-service';
  queuedCreate.phase = 'CREATING';
  queuedCreate.failedManifestVersion = null;
  queuedCreate.lastError = null;
  createQueue.deployments[0].services.push(queuedCreate);
  assert(createQueue.deployments[0].services[1].operation === null, 'create failure must not start the next service');
  runAjv('validate', stateSchema, writeFixture('queue-create-failed', createQueue), true);
  const createRetry = clone(createQueue);
  createRetry.deployments[0].services[0].phase = 'CREATING';
  createRetry.deployments[0].services[0].operation = operation('CREATE', 'STARTING_CANDIDATE', 'instance-retry');
  createRetry.deployments[0].services[0].failedManifestVersion = null;
  createRetry.deployments[0].services[0].lastError = null;
  assert(createRetry.deployments[0].services[0].operation.type === 'CREATE' && createRetry.deployments[0].services[1].operation === null, 'create retry must restore only the failed service');
  runAjv('validate', stateSchema, writeFixture('queue-create-retry', createRetry), true);

  const updateFailure = clone(positiveStates.stable);
  updateFailure.deployments[0].services[0].activeInstance = oldActive;
  updateFailure.deployments[0].services[0].route = routeOld;
  updateFailure.deployments[0].services[0].failedManifestVersion = 2;
  updateFailure.deployments[0].services[0].lastError = error('UPDATE', 'CLEAN_RETRYABLE');
  const queuedUpdate = clone(updateFailure.deployments[0].services[0]);
  queuedUpdate.serviceName = 'agent-tools-service';
  queuedUpdate.failedManifestVersion = null;
  queuedUpdate.lastError = null;
  updateFailure.deployments[0].services.push(queuedUpdate);
  assert(updateFailure.deployments[0].services[1].operation === null, 'update failure must not start the next service');
  runAjv('validate', stateSchema, writeFixture('queue-update-failed', updateFailure), true);
  const updateRetry = clone(updateFailure);
  updateRetry.deployments[0].services[0].phase = 'UPDATING';
  updateRetry.deployments[0].services[0].operation = operation('UPDATE', 'STARTING_CANDIDATE', 'instance-retry');
  updateRetry.deployments[0].services[0].failedManifestVersion = null;
  updateRetry.deployments[0].services[0].lastError = null;
  assert(updateRetry.deployments[0].services[0].operation.type === 'UPDATE' && updateRetry.deployments[0].services[1].operation === null, 'update retry must restore only the failed service');
  runAjv('validate', stateSchema, writeFixture('queue-update-retry', updateRetry), true);

  const deleteRetry = clone(positiveStates.deleteRemoving);
  const laterDelete = clone(deleteRetry.deployments[0].services[0]);
  laterDelete.serviceName = 'agent-tools-service';
  laterDelete.phase = 'STABLE';
  laterDelete.operation = null;
  deleteRetry.deployments[0].services[0].phase = 'FAILED';
  deleteRetry.deployments[0].services[0].operation = null;
  deleteRetry.deployments[0].services[0].failedManifestVersion = 2;
  deleteRetry.deployments[0].services[0].lastError = error('DELETE', 'DELETE_RETRYABLE');
  deleteRetry.deployments[0].services.push(laterDelete);
  assert(deleteRetry.deployments[0].services[1].operation === null, 'delete failure must not start the next service');
  runAjv('validate', stateSchema, writeFixture('queue-delete-failed', deleteRetry), true);
  deleteRetry.deployments[0].services[0].phase = 'DELETING';
  deleteRetry.deployments[0].services[0].operation = operation('DELETE', 'REMOVING_TRAFFIC', null);
  assert(deleteRetry.deployments[0].services[0].operation.type === 'DELETE' && deleteRetry.deployments[0].services[1].operation === null, 'delete retry must restore only the failed delete');
  deleteRetry.deployments[0].services[0].failedManifestVersion = null;
  deleteRetry.deployments[0].services[0].lastError = null;
  runAjv('validate', stateSchema, writeFixture('queue-delete-retry', deleteRetry), true);

  const uncertainFailure = clone(positiveStates.failedCreate);
  uncertainFailure.deployments[0].acceptedManifestVersion = 3;
  uncertainFailure.deployments[0].services[0].targetManifestVersion = 3;
  uncertainFailure.deployments[0].services[0].lastError = error('CREATE', 'FACTS_UNCERTAIN');
  assert(uncertainFailure.deployments[0].services[0].phase === 'FAILED', 'a higher manifest version must not clear uncertain facts');

  console.log(`0-3 contract verification passed: ${schemaChecks} AJV checks, ${contractChecks} contract checks`);
  console.log(`manifestSha256 vector: ${manifestSha256}`);
} finally {
  fs.rmSync(tempDir, {recursive: true, force: true});
}
