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

function deploymentGenerationFromParts(manifestVersion, gitCommit, serviceImages) {
  const lines = [
    'alphafrog-deployment-generation-v1',
    `manifest-version:${manifestVersion}`,
    `git-commit:${gitCommit}`
  ];
  for (const [serviceName, imageReference] of Object.entries(serviceImages)
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0)) {
    lines.push(`service:${serviceName}\0${imageReference}`);
  }
  const bytes = `${lines.join('\n')}\n`;
  return `gen-${crypto.createHash('sha256').update(bytes, 'utf8').digest('hex')}`;
}

function deploymentGeneration(manifestValue) {
  return deploymentGenerationFromParts(
    manifestValue.manifestVersion,
    manifestValue.gitCommit,
    Object.fromEntries(manifestValue.services.map(service => [service.serviceName, service.image.repositoryDigest]))
  );
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
      preStopPolicy: 'AGENT_RETIRE_GENERATION_V1',
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
const expectedManifestSha256 = 'cce91891dc152cbc64b78e068fd2303c7c451b783f147c95135390fdbb28b73d';
const targetGeneration = deploymentGeneration(manifest);
const previousGeneration = `gen-${repeated('8')}`;

const toolsServiceSpec = clone(manifest.services[0]);
toolsServiceSpec.serviceName = 'agent-tools-service';
toolsServiceSpec.releaseId = 'tools-release-2';
toolsServiceSpec.serviceSpecSha256 = repeated('0');
toolsServiceSpec.image.repositoryDigest = `registry.local/agent-tools-service@sha256:${repeated('e')}`;
toolsServiceSpec.image.localImageId = `sha256:${repeated('f')}`;
toolsServiceSpec.runtime.containerPort = 18081;
toolsServiceSpec.runtime.hostPorts = [28180, 28181];
toolsServiceSpec.runtime.preStopPolicy = 'NONE';
toolsServiceSpec.registration.serviceName = 'com.alphafrog.AgentToolsService:1.0@@providers';
toolsServiceSpec.serviceSpecSha256 = serviceDigest(toolsServiceSpec);
const toolsTargetSpec = toolsServiceSpec.serviceSpecSha256;
const twoServiceManifest = clone(manifest);
twoServiceManifest.services.push(toolsServiceSpec);
const twoServiceManifestSha256 = digest(twoServiceManifest);
const twoServiceGeneration = deploymentGeneration(twoServiceManifest);

function registration(instanceId, releaseId, generationId, port, selectable, registeredServiceName = 'com.alphafrog.AgentService:1.0@@providers') {
  return {
    serviceName: registeredServiceName,
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
      'alphafrog.deployment-generation-id': generationId,
      'alphafrog.instance-id': instanceId
    }
  };
}

function baseInstance(instanceId, releaseId, generationId, version, spec, slot, port, selectable = true,
  registeredServiceName = 'com.alphafrog.AgentService:1.0@@providers', runtimeFacts = manifest.services[0].runtime) {
  return {
    instanceId,
    machineId: 'beta-machine-1',
    releaseId,
    deploymentGenerationId: generationId,
    preStopPolicy: runtimeFacts.preStopPolicy,
    shutdownProfile: runtimeFacts.shutdownProfile,
    applicationDrainSeconds: runtimeFacts.applicationDrainSeconds,
    drainGraceSeconds: runtimeFacts.drainGraceSeconds,
    manifestVersion: version,
    serviceSpecSha256: spec,
    containerName: `af-${instanceId}`,
    containerId: crypto.createHash('sha256').update(`container:${instanceId}`, 'utf8').digest('hex'),
    portSlot: slot,
    hostPort: port,
    endpoint: {address: '10.0.0.8', port},
    registration: registration(instanceId, releaseId, generationId, port, selectable, registeredServiceName)
  };
}

const previousAgentRuntime = {...manifest.services[0].runtime, applicationDrainSeconds: 40, drainGraceSeconds: 45};
const oldActive = baseInstance('instance-old', 'release-1', previousGeneration, 1, repeated('d'), 'A', 28080, true,
  'com.alphafrog.AgentService:1.0@@providers', previousAgentRuntime);
const newActive = baseInstance('instance-new', 'release-2', targetGeneration, 2, targetSpec, 'B', 28081);
const newDisabled = baseInstance('instance-new', 'release-2', targetGeneration, 2, targetSpec, 'B', 28081, false);
const candidateStarting = {...newDisabled, readiness: 'STARTING', readinessObservedAt: null, readinessDeadline: '2026-09-01T00:02:00Z'};
const candidateReady = {...newDisabled, readiness: 'READY', readinessObservedAt: '2026-09-01T00:01:00Z', readinessDeadline: '2026-09-01T00:02:00Z'};
const oldDrainingSelectable = {...clone(oldActive), trafficRemovedAt: '2026-09-01T00:02:00Z', stopSignalRequestedAt: null, stopDeadline: null, preStopCompletedAt: null};
const oldDisabled = clone(oldDrainingSelectable);
oldDisabled.registration.enabled = false;
oldDisabled.registration.weight = 0;
const newDisabledDraining = {...clone(newActive), trafficRemovedAt: '2026-09-01T00:02:00Z', stopSignalRequestedAt: null, stopDeadline: null, preStopCompletedAt: null};
newDisabledDraining.registration.enabled = false;
newDisabledDraining.registration.weight = 0;
const toolsOldActive = baseInstance('tools-instance-old', 'tools-release-1', previousGeneration, 1, repeated('9'), 'A', 28180, true, 'com.alphafrog.AgentToolsService:1.0@@providers', toolsServiceSpec.runtime);
const toolsNewActive = baseInstance('tools-instance-new', 'tools-release-2', twoServiceGeneration, 2, toolsTargetSpec, 'B', 28181, true, 'com.alphafrog.AgentToolsService:1.0@@providers', toolsServiceSpec.runtime);

const routeOld = {
  defaultInstanceId: 'instance-old',
  defaultReleaseId: 'release-1',
  defaultDeploymentGenerationId: previousGeneration,
  routeVersion: 7,
  updatedAt: '2026-09-01T00:00:00Z'
};
const routeNewStable = {...routeOld, defaultInstanceId: 'instance-new', defaultReleaseId: 'release-2', defaultDeploymentGenerationId: targetGeneration, routeVersion: 8, updatedAt: '2026-09-01T00:02:00Z'};
const routeNone = {...routeOld, defaultInstanceId: null, defaultReleaseId: null, defaultDeploymentGenerationId: null, routeVersion: 0};
const routeNoneAfterDelete = {...routeNone, routeVersion: 9, updatedAt: '2026-09-01T00:02:00Z'};
const toolsRouteOld = {...routeOld, defaultInstanceId: toolsOldActive.instanceId, defaultReleaseId: toolsOldActive.releaseId, defaultDeploymentGenerationId: toolsOldActive.deploymentGenerationId, routeVersion: 4};
const toolsRouteNew = {...routeNewStable, defaultInstanceId: toolsNewActive.instanceId, defaultReleaseId: toolsNewActive.releaseId, defaultDeploymentGenerationId: toolsNewActive.deploymentGenerationId, routeVersion: 5};

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

function twoServiceState(firstService, secondService, deploymentOverrides = {}) {
  const serviceStates = clone([firstService, secondService]);
  const result = state(serviceStates[0], {
    manifestSha256: twoServiceManifestSha256,
    services: serviceStates,
    ...deploymentOverrides
  });
  for (const serviceState of result.deployments[0].services) {
    for (const instance of [serviceState.activeInstance, serviceState.candidateInstance, serviceState.drainingInstance].filter(Boolean)) {
      if (instance.manifestVersion === twoServiceManifest.manifestVersion) {
        instance.deploymentGenerationId = twoServiceGeneration;
        instance.registration.metadata['alphafrog.deployment-generation-id'] = twoServiceGeneration;
      }
    }
    if (serviceState.route.defaultInstanceId !== null) {
      const routed = [serviceState.activeInstance, serviceState.candidateInstance, serviceState.drainingInstance]
        .find(instance => instance?.instanceId === serviceState.route.defaultInstanceId);
      if (routed) serviceState.route.defaultDeploymentGenerationId = routed.deploymentGenerationId;
    }
  }
  return result;
}

function toolsService(overrides = {}) {
  return service({
    serviceName: 'agent-tools-service',
    targetServiceSpecSha256: toolsTargetSpec,
    activeInstance: toolsNewActive,
    route: toolsRouteNew,
    ...overrides
  });
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
  updateDraining: state(service({
    phase: 'UPDATING', activeInstance: newActive, drainingInstance: oldDrainingSelectable, route: routeNewStable,
    operation: operation('UPDATE', 'DRAINING_PREVIOUS', null)
  })),
  deleteRemoving: state(service({
    phase: 'DELETING', operation: operation('DELETE', 'REMOVING_TRAFFIC', null)
  }), {phase: 'DELETING'}),
  deleteDraining: state(service({
    phase: 'DELETING', activeInstance: null, drainingInstance: newDisabledDraining,
    route: routeNoneAfterDelete, operation: operation('DELETE', 'DRAINING_ACTIVE', null)
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
  const expectedGeneration = deploymentGeneration(manifestValue);
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
  const containerIds = new Set();
  for (const deployment of stateValue.deployments) {
    if (deploymentIds.has(deployment.deploymentId)) errors.push(`duplicate deployment ${deployment.deploymentId}`);
    if (trafficScopes.has(deployment.trafficScopeId)) errors.push(`duplicate traffic scope ${deployment.trafficScopeId}`);
    deploymentIds.add(deployment.deploymentId);
    trafficScopes.add(deployment.trafficScopeId);
    if (deployment.manifestSha256 !== digest(manifestValue)) errors.push('manifest digest');
    if (deployment.acceptedManifestVersion !== manifestValue.manifestVersion) errors.push('manifest version');
    const stateServiceNames = new Set();
    for (const item of deployment.services) {
      if (stateServiceNames.has(item.serviceName)) errors.push(`duplicate state service ${item.serviceName}`);
      stateServiceNames.add(item.serviceName);
      if (item.operation) operationCount++;
      const spec = specByName.get(item.serviceName);
      if (!spec) errors.push(`state service missing from manifest ${item.serviceName}`);
      if (spec && item.activeInstance?.preStopPolicy === 'AGENT_RETIRE_GENERATION_V1'
          && spec.runtime.preStopPolicy !== 'AGENT_RETIRE_GENERATION_V1') {
        errors.push(`agent pre-stop policy downgrade ${item.serviceName}`);
      }
      if (spec && item.targetManifestVersion !== manifestValue.manifestVersion) errors.push(`target manifest version ${item.serviceName}`);
      if (spec && item.targetServiceSpecSha256 !== spec.serviceSpecSha256) errors.push(`target service digest ${item.serviceName}`);
      if ((item.failedManifestVersion === null) !== (item.lastError === null)) errors.push(`failure fields pair ${item.serviceName}`);
      const roleSlots = new Set();
      for (const instance of [item.activeInstance, item.candidateInstance, item.drainingInstance].filter(Boolean)) {
        if (instanceIds.has(instance.instanceId)) errors.push(`duplicate instance ${instance.instanceId}`);
        instanceIds.add(instance.instanceId);
        if (containerIds.has(instance.containerId)) errors.push(`duplicate container ${instance.containerId}`);
        containerIds.add(instance.containerId);
        if (roleSlots.has(instance.portSlot)) errors.push(`duplicate role slot ${item.serviceName}:${instance.portSlot}`);
        roleSlots.add(instance.portSlot);
        const registrationValue = instance.registration;
        if (registrationValue.metadata['alphafrog.traffic-scope-id'] !== deployment.trafficScopeId) errors.push(`scope metadata ${instance.instanceId}`);
        if (registrationValue.metadata['alphafrog.release-id'] !== instance.releaseId) errors.push(`release metadata ${instance.instanceId}`);
        if (registrationValue.metadata['alphafrog.deployment-generation-id'] !== instance.deploymentGenerationId) errors.push(`generation metadata ${instance.instanceId}`);
        if (registrationValue.metadata['alphafrog.instance-id'] !== instance.instanceId) errors.push(`instance metadata ${instance.instanceId}`);
        if (registrationValue.port !== instance.hostPort || instance.endpoint.port !== instance.hostPort) errors.push(`endpoint ${instance.instanceId}`);
        if (spec && !spec.runtime.hostPorts.includes(instance.hostPort)) errors.push(`port slot ${instance.instanceId}`);
        if (spec && instance.manifestVersion === manifestValue.manifestVersion
            && instance.serviceSpecSha256 === spec.serviceSpecSha256
            && instance.deploymentGenerationId !== expectedGeneration) {
          errors.push(`deployment generation ${instance.instanceId}`);
        }
        if (spec && instance.manifestVersion === manifestValue.manifestVersion
            && instance.serviceSpecSha256 === spec.serviceSpecSha256
            && instance.preStopPolicy !== spec.runtime.preStopPolicy) {
          errors.push(`pre-stop policy ${instance.instanceId}`);
        }
        if (spec && instance.manifestVersion === manifestValue.manifestVersion
            && instance.serviceSpecSha256 === spec.serviceSpecSha256
            && (instance.shutdownProfile !== spec.runtime.shutdownProfile
              || instance.applicationDrainSeconds !== spec.runtime.applicationDrainSeconds
              || instance.drainGraceSeconds !== spec.runtime.drainGraceSeconds)) {
          errors.push(`shutdown policy ${instance.instanceId}`);
        }
        if (instance.applicationDrainSeconds + 5 > instance.drainGraceSeconds) errors.push(`instance drain reserve ${instance.instanceId}`);
      }
      if (item.activeInstance && (!item.activeInstance.registration.enabled || item.activeInstance.registration.weight !== 1 || !item.activeInstance.registration.healthy)) errors.push(`active not selectable ${item.serviceName}`);
      if (item.candidateInstance && (item.candidateInstance.registration.enabled || item.candidateInstance.registration.weight !== 0)) errors.push(`candidate selectable ${item.serviceName}`);
      if (item.operation?.phase === 'SWITCHING_TRAFFIC' && (!item.candidateInstance?.registration.healthy || item.candidateInstance?.readiness !== 'READY')) errors.push(`candidate not ready ${item.serviceName}`);
      if (item.drainingInstance) {
        const {enabled, weight} = item.drainingInstance.registration;
        if (!((enabled && weight === 1) || (!enabled && weight === 0))) errors.push(`draining registration pair ${item.serviceName}`);
        if (item.drainingInstance.preStopCompletedAt !== null && enabled) errors.push(`pre-stop before registration disabled ${item.serviceName}`);
        if (item.drainingInstance.preStopCompletedAt !== null
            && Date.parse(item.drainingInstance.preStopCompletedAt) < Date.parse(item.drainingInstance.trafficRemovedAt)) {
          errors.push(`pre-stop before drain ${item.serviceName}`);
        }
        if ((item.drainingInstance.stopSignalRequestedAt === null) !== (item.drainingInstance.stopDeadline === null)) {
          errors.push(`stop timing pair ${item.serviceName}`);
        }
        if (item.drainingInstance.stopSignalRequestedAt !== null) {
          if (item.drainingInstance.preStopCompletedAt === null) errors.push(`stop before pre-stop ${item.serviceName}`);
          if (Date.parse(item.drainingInstance.stopSignalRequestedAt) < Date.parse(item.drainingInstance.preStopCompletedAt)) errors.push(`stop timing order ${item.serviceName}`);
          if (Date.parse(item.drainingInstance.stopDeadline) <= Date.parse(item.drainingInstance.stopSignalRequestedAt)) errors.push(`stop deadline ${item.serviceName}`);
          if (Date.parse(item.drainingInstance.stopDeadline) - Date.parse(item.drainingInstance.stopSignalRequestedAt)
              !== item.drainingInstance.drainGraceSeconds * 1000) errors.push(`stop grace ${item.serviceName}`);
        }
      }
      if (item.operation?.phase === 'DRAINING_PREVIOUS') {
        if (item.route.defaultInstanceId !== item.activeInstance?.instanceId
            || item.route.defaultReleaseId !== item.activeInstance?.releaseId
            || item.route.defaultDeploymentGenerationId !== item.activeInstance?.deploymentGenerationId) errors.push(`draining route ${item.serviceName}`);
        if (item.drainingInstance?.trafficRemovedAt !== item.route.updatedAt) errors.push(`draining switch time ${item.serviceName}`);
      }
      if (item.operation?.phase === 'DRAINING_ACTIVE') {
        if (item.route.defaultInstanceId !== null || item.route.defaultReleaseId !== null
            || item.route.defaultDeploymentGenerationId !== null) errors.push(`delete route ${item.serviceName}`);
        if (item.drainingInstance?.trafficRemovedAt !== item.route.updatedAt) errors.push(`delete switch time ${item.serviceName}`);
      }
      if (item.lastError?.recoveryClass === 'DELETE_RETRYABLE' && item.lastError.failedOperationType !== 'DELETE') errors.push(`delete recovery class ${item.serviceName}`);
      if (item.lastError?.recoveryClass === 'CLEAN_RETRYABLE' && item.lastError.failedOperationType === 'DELETE') errors.push(`clean recovery class ${item.serviceName}`);
      if (item.phase === 'STABLE' && item.activeInstance
          && (item.route.defaultInstanceId !== item.activeInstance.instanceId
            || item.route.defaultReleaseId !== item.activeInstance.releaseId
            || item.route.defaultDeploymentGenerationId !== item.activeInstance.deploymentGenerationId)) errors.push(`stable route ${item.serviceName}`);
    }
    if (deployment.services.some(item => item.lastError !== null) && deployment.services.some(item => item.operation !== null)) errors.push(`operation while deployment failure is paused ${deployment.deploymentId}`);
    if (deployment.phase === 'ACTIVE' && stateServiceNames.size !== specByName.size) errors.push(`active deployment service set ${deployment.deploymentId}`);
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
  const failedVersionWithoutError = clone(positiveStates.stable);
  failedVersionWithoutError.deployments[0].services[0].failedManifestVersion = 2;
  invalidFixtures.push(['state-failed-version-without-error', stateSchema, failedVersionWithoutError]);
  const errorWithoutFailedVersion = clone(positiveStates.stable);
  errorWithoutFailedVersion.deployments[0].services[0].lastError = error('UPDATE', 'CLEAN_RETRYABLE');
  invalidFixtures.push(['state-error-without-failed-version', stateSchema, errorWithoutFailedVersion]);
  const reservedDeployment = clone(manifest);
  reservedDeployment.deploymentId = 'stable';
  invalidFixtures.push(['manifest-reserved-deployment', manifestSchema, reservedDeployment]);
  const sameHostPorts = clone(manifest);
  sameHostPorts.services[0].runtime.hostPorts = [28080, 28080];
  invalidFixtures.push(['manifest-same-host-ports', manifestSchema, sameHostPorts]);
  const mutableImage = clone(manifest);
  mutableImage.services[0].image.repositoryDigest = 'registry.local/agent-service:latest';
  invalidFixtures.push(['manifest-mutable-image', manifestSchema, mutableImage]);
  const unsupportedPreStop = clone(manifest);
  unsupportedPreStop.services[0].runtime.preStopPolicy = 'RUN_ARBITRARY_SCRIPT';
  invalidFixtures.push(['manifest-unsupported-pre-stop', manifestSchema, unsupportedPreStop]);
  const missingDeploymentGeneration = clone(positiveStates.stable);
  delete missingDeploymentGeneration.deployments[0].services[0].activeInstance.deploymentGenerationId;
  invalidFixtures.push(['state-missing-deployment-generation', stateSchema, missingDeploymentGeneration]);
  const missingRouteGeneration = clone(positiveStates.stable);
  delete missingRouteGeneration.deployments[0].services[0].route.defaultDeploymentGenerationId;
  invalidFixtures.push(['state-missing-route-generation', stateSchema, missingRouteGeneration]);
  const missingPreStopCheckpoint = clone(positiveStates.updateDraining);
  delete missingPreStopCheckpoint.deployments[0].services[0].drainingInstance.preStopCompletedAt;
  invalidFixtures.push(['state-missing-pre-stop-checkpoint', stateSchema, missingPreStopCheckpoint]);
  const obsoleteRouteLease = clone(manifest);
  obsoleteRouteLease.services[0].runtime.routeLeaseSeconds = 30;
  invalidFixtures.push(['manifest-obsolete-route-lease', manifestSchema, obsoleteRouteLease]);
  const obsoleteRouteWindow = clone(positiveStates.stable);
  obsoleteRouteWindow.deployments[0].services[0].route.previousVersionValidUntil = '2026-09-01T00:02:30Z';
  invalidFixtures.push(['state-obsolete-route-window', stateSchema, obsoleteRouteWindow]);
  const obsoleteUpdatePhase = clone(positiveStates.updateDraining);
  obsoleteUpdatePhase.deployments[0].services[0].operation.phase = 'WAITING_OLD_ROUTE_LEASES';
  invalidFixtures.push(['state-obsolete-update-lease-phase', stateSchema, obsoleteUpdatePhase]);
  const obsoleteDeletePhase = clone(positiveStates.deleteDraining);
  obsoleteDeletePhase.deployments[0].services[0].operation.phase = 'WAITING_DELETE_ROUTE_LEASES';
  invalidFixtures.push(['state-obsolete-delete-lease-phase', stateSchema, obsoleteDeletePhase]);
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
  const invalidDrainingRegistration = clone(positiveStates.updateDraining);
  invalidDrainingRegistration.deployments[0].services[0].drainingInstance.registration.enabled = true;
  invalidDrainingRegistration.deployments[0].services[0].drainingInstance.registration.weight = 0;
  customNegatives.push(['draining registration pair', manifest, invalidDrainingRegistration]);
  const prematurePreStopCheckpoint = clone(positiveStates.updateDraining);
  prematurePreStopCheckpoint.deployments[0].services[0].drainingInstance.preStopCompletedAt = '2026-09-01T00:02:10Z';
  customNegatives.push(['pre-stop before registration disabled', manifest, prematurePreStopCheckpoint]);
  const badMetadata = clone(positiveStates.stable);
  badMetadata.deployments[0].services[0].activeInstance.registration.metadata['alphafrog.release-id'] = 'wrong-release';
  customNegatives.push(['registration metadata', manifest, badMetadata]);
  const badGenerationMetadata = clone(positiveStates.stable);
  badGenerationMetadata.deployments[0].services[0].activeInstance.registration.metadata['alphafrog.deployment-generation-id'] = previousGeneration;
  customNegatives.push(['registration generation metadata', manifest, badGenerationMetadata]);
  const badServiceDigest = clone(manifest);
  badServiceDigest.services[0].serviceSpecSha256 = repeated('f');
  customNegatives.push(['service digest', badServiceDigest, positiveStates.stable]);
  const wrongDeploymentGeneration = clone(positiveStates.stable);
  wrongDeploymentGeneration.deployments[0].services[0].activeInstance.deploymentGenerationId = `gen-${repeated('7')}`;
  customNegatives.push(['deployment generation', manifest, wrongDeploymentGeneration]);
  const wrongPreStopPolicy = clone(positiveStates.stable);
  wrongPreStopPolicy.deployments[0].services[0].activeInstance.preStopPolicy = 'NONE';
  customNegatives.push(['pre-stop policy', manifest, wrongPreStopPolicy]);
  const wrongShutdownPolicy = clone(positiveStates.stable);
  wrongShutdownPolicy.deployments[0].services[0].activeInstance.applicationDrainSeconds = 54;
  customNegatives.push(['shutdown policy', manifest, wrongShutdownPolicy]);
  const downgradedPolicyManifest = clone(manifest);
  downgradedPolicyManifest.services[0].runtime.preStopPolicy = 'NONE';
  downgradedPolicyManifest.services[0].serviceSpecSha256 = serviceDigest(downgradedPolicyManifest.services[0]);
  const downgradedPolicyState = clone(positiveStates.updateSwitching);
  downgradedPolicyState.deployments[0].manifestSha256 = digest(downgradedPolicyManifest);
  downgradedPolicyState.deployments[0].services[0].targetServiceSpecSha256 = downgradedPolicyManifest.services[0].serviceSpecSha256;
  downgradedPolicyState.deployments[0].services[0].candidateInstance.serviceSpecSha256 = downgradedPolicyManifest.services[0].serviceSpecSha256;
  downgradedPolicyState.deployments[0].services[0].candidateInstance.preStopPolicy = 'NONE';
  customNegatives.push(['agent pre-stop policy downgrade', downgradedPolicyManifest, downgradedPolicyState]);
  const duplicateServiceManifest = clone(manifest);
  duplicateServiceManifest.services.push(clone(duplicateServiceManifest.services[0]));
  customNegatives.push(['duplicate service', duplicateServiceManifest, positiveStates.stable]);
  const duplicateSlot = clone(positiveStates.updateSwitching);
  duplicateSlot.deployments[0].services[0].candidateInstance.portSlot = 'A';
  customNegatives.push(['duplicate role slot', manifest, duplicateSlot]);
  const duplicateContainer = clone(positiveStates.updateSwitching);
  duplicateContainer.deployments[0].services[0].candidateInstance.containerId = duplicateContainer.deployments[0].services[0].activeInstance.containerId;
  customNegatives.push(['duplicate container', manifest, duplicateContainer]);
  const stableRouteMismatch = clone(positiveStates.stable);
  stableRouteMismatch.deployments[0].services[0].route.defaultInstanceId = 'instance-old';
  customNegatives.push(['stable route mismatch', manifest, stableRouteMismatch]);
  const stableRouteGenerationMismatch = clone(positiveStates.stable);
  stableRouteGenerationMismatch.deployments[0].services[0].route.defaultDeploymentGenerationId = previousGeneration;
  customNegatives.push(['stable route generation mismatch', manifest, stableRouteGenerationMismatch]);
  const drainingSwitchMismatch = clone(positiveStates.updateDraining);
  drainingSwitchMismatch.deployments[0].services[0].drainingInstance.trafficRemovedAt = '2026-09-01T00:02:01Z';
  customNegatives.push(['draining switch time', manifest, drainingSwitchMismatch]);
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
  assert(manifestSha256 === expectedManifestSha256, 'manifest digest must match the published fixed vector');
  const generationVector = JSON.parse(fs.readFileSync(path.join(here, '..', 'agent-run', 'deployment-generation-test-vector.json'), 'utf8'));
  assert(deploymentGenerationFromParts(
    generationVector.manifestVersion,
    generationVector.gitCommit,
    generationVector.serviceImages
  ) === generationVector.deploymentGenerationId, 'deployment generation vector must stay fixed');
  runAjv('validate', manifestSchema, writeFixture('manifest-two-service-valid', twoServiceManifest), true);

  function validateState(name, manifestValue, stateValue) {
    runAjv('validate', stateSchema, writeFixture(name, stateValue), true);
    const errors = customErrors(manifestValue, stateValue);
    assert(errors.length === 0, `${name} custom checks should pass: ${errors.join(', ')}`);
  }

  function retryTransitionErrors(beforeState, afterState, action) {
    const errors = [];
    if (afterState.stateVersion !== beforeState.stateVersion + 1) errors.push('state version must increase by exactly one');
    const beforeServices = beforeState.deployments[0].services;
    const afterServices = afterState.deployments[0].services;
    const failed = beforeServices.find(item => item.lastError !== null);
    if (!failed) return ['missing failed service'];
    if (action.serviceName !== failed.serviceName) errors.push('retry skipped the failed service');
    if (failed.lastError.recoveryClass === 'FACTS_UNCERTAIN' && action.factsReconciled !== true) errors.push('uncertain facts require manual reconciliation');
    const afterFailed = afterServices.find(item => item.serviceName === failed.serviceName);
    const expected = {
      CREATE: {action: 'RETRY_CREATE', phase: 'CREATING'},
      UPDATE: {action: 'RETRY_UPDATE', phase: 'UPDATING'},
      DELETE: {action: 'RETRY_DELETE', phase: 'DELETING'}
    }[failed.lastError.failedOperationType];
    if (!expected || action.type !== expected.action) errors.push('retry action does not match failed operation');
    if (!afterFailed || afterFailed.phase !== expected?.phase || afterFailed.operation?.type !== failed.lastError.failedOperationType) errors.push('failed service did not restart the matching operation');
    if (afterFailed?.failedManifestVersion !== null || afterFailed?.lastError !== null) errors.push('retry did not clear the accepted failure atomically');
    for (const beforeOther of beforeServices.filter(item => item.serviceName !== failed.serviceName)) {
      const afterOther = afterServices.find(item => item.serviceName === beforeOther.serviceName);
      for (const field of ['phase', 'activeInstance', 'candidateInstance', 'drainingInstance', 'route', 'operation', 'failedManifestVersion', 'lastError']) {
        if (canonicalize(afterOther?.[field]) !== canonicalize(beforeOther[field])) errors.push(`retry changed later service ${beforeOther.serviceName}`);
      }
    }
    return errors;
  }

  const createFailed = service({
    phase: 'FAILED', activeInstance: null, route: routeNone, failedManifestVersion: 2,
    lastError: error('CREATE', 'CLEAN_RETRYABLE')
  });
  const toolsCreateQueued = toolsService({
    phase: 'CREATING', activeInstance: null, candidateInstance: null, drainingInstance: null,
    route: routeNone, operation: null
  });
  const createQueue = twoServiceState(createFailed, toolsCreateQueued);
  validateState('queue-create-failed', twoServiceManifest, createQueue);
  assert(createQueue.deployments[0].services[1].operation === null, 'create failure must not start the next service');
  const createRetry = clone(createQueue);
  createRetry.stateVersion++;
  Object.assign(createRetry.deployments[0].services[0], {
    phase: 'CREATING', operation: operation('CREATE', 'STARTING_CANDIDATE', 'instance-retry'),
    failedManifestVersion: null, lastError: null
  });
  validateState('queue-create-retry', twoServiceManifest, createRetry);
  assert(retryTransitionErrors(createQueue, createRetry, {type: 'RETRY_CREATE', serviceName: 'agent-service'}).length === 0, 'create retry transition must restore only the failed service');
  const unchangedStateVersion = clone(createRetry);
  unchangedStateVersion.stateVersion = createQueue.stateVersion;
  assert(retryTransitionErrors(createQueue, unchangedStateVersion, {type: 'RETRY_CREATE', serviceName: 'agent-service'}).length > 0, 'retry must reject an unchanged state version');
  const reversedStateVersion = clone(createRetry);
  reversedStateVersion.stateVersion = createQueue.stateVersion - 1;
  assert(retryTransitionErrors(createQueue, reversedStateVersion, {type: 'RETRY_CREATE', serviceName: 'agent-service'}).length > 0, 'retry must reject a lower state version');
  const skippedStateVersion = clone(createRetry);
  skippedStateVersion.stateVersion = createQueue.stateVersion + 2;
  assert(retryTransitionErrors(createQueue, skippedStateVersion, {type: 'RETRY_CREATE', serviceName: 'agent-service'}).length > 0, 'retry must reject a state version jump');

  const updateFailed = service({
    activeInstance: oldActive, route: routeOld, failedManifestVersion: 2,
    lastError: error('UPDATE', 'CLEAN_RETRYABLE')
  });
  const toolsUpdateQueued = toolsService({activeInstance: toolsOldActive, route: toolsRouteOld});
  const updateFailure = twoServiceState(updateFailed, toolsUpdateQueued);
  validateState('queue-update-failed', twoServiceManifest, updateFailure);
  assert(updateFailure.deployments[0].services[1].operation === null, 'update failure must not start the next service');
  const updateRetry = clone(updateFailure);
  updateRetry.stateVersion++;
  Object.assign(updateRetry.deployments[0].services[0], {
    phase: 'UPDATING', operation: operation('UPDATE', 'STARTING_CANDIDATE', 'instance-retry'),
    failedManifestVersion: null, lastError: null
  });
  validateState('queue-update-retry', twoServiceManifest, updateRetry);
  assert(retryTransitionErrors(updateFailure, updateRetry, {type: 'RETRY_UPDATE', serviceName: 'agent-service'}).length === 0, 'update retry transition must restore only the failed service');

  const deleteFailed = service({
    phase: 'FAILED', failedManifestVersion: 2,
    lastError: error('DELETE', 'DELETE_RETRYABLE')
  });
  const deleteFailure = twoServiceState(deleteFailed, toolsService(), {phase: 'DELETING'});
  validateState('queue-delete-failed', twoServiceManifest, deleteFailure);
  assert(deleteFailure.deployments[0].services[1].operation === null, 'delete failure must not start the next service');
  const deleteRetry = clone(deleteFailure);
  deleteRetry.stateVersion++;
  Object.assign(deleteRetry.deployments[0].services[0], {
    phase: 'DELETING', operation: operation('DELETE', 'REMOVING_TRAFFIC', null),
    failedManifestVersion: null, lastError: null
  });
  validateState('queue-delete-retry', twoServiceManifest, deleteRetry);
  assert(retryTransitionErrors(deleteFailure, deleteRetry, {type: 'RETRY_DELETE', serviceName: 'agent-service'}).length === 0, 'delete retry transition must restore only the failed service');

  const uncertainFailure = clone(createQueue);
  uncertainFailure.deployments[0].services[0].lastError = error('CREATE', 'FACTS_UNCERTAIN');
  validateState('queue-create-uncertain', twoServiceManifest, uncertainFailure);
  const version3Manifest = clone(twoServiceManifest);
  version3Manifest.manifestVersion = 3;
  version3Manifest.gitCommit = '2'.repeat(40);
  runAjv('validate', manifestSchema, writeFixture('manifest-two-service-v3-valid', version3Manifest), true);
  const illegalHigherVersionRetry = clone(uncertainFailure);
  illegalHigherVersionRetry.stateVersion++;
  Object.assign(illegalHigherVersionRetry.deployments[0], {
    acceptedManifestVersion: 3,
    manifestSha256: digest(version3Manifest),
    gitCommit: version3Manifest.gitCommit
  });
  for (const item of illegalHigherVersionRetry.deployments[0].services) item.targetManifestVersion = 3;
  Object.assign(illegalHigherVersionRetry.deployments[0].services[0], {
    phase: 'CREATING', operation: operation('CREATE', 'STARTING_CANDIDATE', 'instance-illegal-retry'),
    failedManifestVersion: null, lastError: null
  });
  validateState('queue-create-uncertain-illegal-shape', version3Manifest, illegalHigherVersionRetry);
  assert(retryTransitionErrors(uncertainFailure, illegalHigherVersionRetry, {type: 'ACCEPT_HIGHER_MANIFEST', serviceName: 'agent-service', factsReconciled: false}).length > 0, 'a higher manifest alone must not clear uncertain facts');
  const manualRetry = clone(uncertainFailure);
  manualRetry.stateVersion++;
  Object.assign(manualRetry.deployments[0].services[0], {
    phase: 'CREATING', operation: operation('CREATE', 'STARTING_CANDIDATE', 'instance-manual-retry'),
    failedManifestVersion: null, lastError: null
  });
  validateState('queue-create-uncertain-manual-retry', twoServiceManifest, manualRetry);
  assert(retryTransitionErrors(uncertainFailure, manualRetry, {type: 'RETRY_CREATE', serviceName: 'agent-service', factsReconciled: true}).length === 0, 'manual reconciliation must allow the failed service to retry');
  const skippedFailure = clone(uncertainFailure);
  skippedFailure.deployments[0].services[1].operation = operation('CREATE', 'STARTING_CANDIDATE', 'tools-illegal-retry');
  assert(customErrors(twoServiceManifest, skippedFailure).length > 0, 'a retry must not skip the current failed service');

  let currentRoute = clone(routeOld);
  let routeReads = 0;
  const instancesById = new Map([
    [oldActive.instanceId, oldActive],
    [newActive.instanceId, newActive]
  ]);
  function bindNewCall() {
    routeReads++;
    const pointer = clone(currentRoute);
    if (pointer.defaultInstanceId === null) throw new Error('BETA_DEFAULT_ROUTE_UNAVAILABLE');
    const instance = instancesById.get(pointer.defaultInstanceId);
    if (!instance || instance.releaseId !== pointer.defaultReleaseId) throw new Error('BETA_ROUTE_FACTS_UNCERTAIN');
    return {instanceId: instance.instanceId, endpoint: clone(instance.endpoint), routeVersion: pointer.routeVersion};
  }

  const boundBeforeSwitch = bindNewCall();
  currentRoute = clone(routeNewStable);
  const firstAfterSwitch = bindNewCall();
  const secondAfterSwitch = bindNewCall();
  assert(boundBeforeSwitch.instanceId === 'instance-old', 'a call bound before the switch must remain on the old instance');
  assert(firstAfterSwitch.instanceId === 'instance-new' && secondAfterSwitch.instanceId === 'instance-new', 'every call started after the switch must bind the new instance');
  assert(routeReads === 3, 'the mutable route pointer must be read once for every new call');
  assert(boundBeforeSwitch.instanceId === 'instance-old' && boundBeforeSwitch.routeVersion === 7, 'the atomic switch must not rewrite an in-flight binding');

  currentRoute = clone(routeNoneAfterDelete);
  let deleteFailedClosed = false;
  try {
    bindNewCall();
  } catch (errorValue) {
    deleteFailedClosed = errorValue.message === 'BETA_DEFAULT_ROUTE_UNAVAILABLE';
  }
  assert(deleteFailedClosed, 'a call started after route removal must fail closed');

  const postSwitch = clone(positiveStates.updateDraining.deployments[0].services[0]);
  assert(postSwitch.drainingInstance.registration.enabled, 'the old registration may still be selectable immediately after the atomic pointer switch');
  postSwitch.drainingInstance.registration.enabled = false;
  postSwitch.drainingInstance.registration.weight = 0;
  const routeReadbackMatches = postSwitch.route.routeVersion === routeNewStable.routeVersion
    && postSwitch.route.defaultInstanceId === postSwitch.activeInstance.instanceId;
  const retirementRequest = {
    deploymentId: manifest.deploymentId,
    deploymentGenerationId: postSwitch.drainingInstance.deploymentGenerationId
  };
  const retirementAccepted = postSwitch.drainingInstance.preStopPolicy === 'AGENT_RETIRE_GENERATION_V1'
    && retirementRequest.deploymentId === manifest.deploymentId
    && retirementRequest.deploymentGenerationId === postSwitch.drainingInstance.deploymentGenerationId;
  if (retirementAccepted) postSwitch.drainingInstance.preStopCompletedAt = '2026-09-01T00:02:10Z';
  postSwitch.drainingInstance.stopSignalRequestedAt = '2026-09-01T00:02:11Z';
  postSwitch.drainingInstance.stopDeadline = '2026-09-01T00:02:56Z';
  assert(routeReadbackMatches && !postSwitch.drainingInstance.registration.enabled && retirementAccepted
      && postSwitch.drainingInstance.preStopCompletedAt !== null,
    'SIGTERM requires exact route readback, a disabled old registration, and a successful configured pre-stop hook');

  console.log(`Beta deployment traffic contract verification passed: ${schemaChecks} AJV checks, ${contractChecks} contract checks`);
  console.log(`manifestSha256 vector: ${manifestSha256}`);
} finally {
  fs.rmSync(tempDir, {recursive: true, force: true});
}
