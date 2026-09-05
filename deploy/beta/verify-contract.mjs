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
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'alphafrog-beta-contract-'));
const clone = structuredClone;
let schemaChecks = 0;
let relationChecks = 0;

function canonical(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
}

const digest = value => crypto.createHash('sha256').update(canonical(value)).digest('hex');
const serviceDigest = value => {
  const copy = clone(value);
  delete copy.serviceSpecSha256;
  return digest(copy);
};
const generation = value => {
  const lines = ['alphafrog-deployment-generation-v1', `manifest-version:${value.manifestVersion}`, `git-commit:${value.gitCommit}`];
  for (const service of [...value.services].sort((a, b) => a.serviceName.localeCompare(b.serviceName))) {
    lines.push(`service:${service.serviceName}\0${service.image.repositoryDigest}`);
  }
  return `gen-${crypto.createHash('sha256').update(`${lines.join('\n')}\n`).digest('hex')}`;
};
const dubboProvider = serviceKey => {
  const match = /^([0-9A-Za-z._-]+)\/([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*)(?::([0-9A-Za-z._-]+))?$/.exec(serviceKey);
  if (!match) throw new Error(`invalid Dubbo service key: ${serviceKey}`);
  return {group: match[1], interfaceName: match[2], version: match[3] ?? ''};
};
const fixture = (name, value) => {
  const target = path.join(temp, `${name}.json`);
  fs.writeFileSync(target, `${JSON.stringify(value, null, 2)}\n`);
  return target;
};
function ajv(schema, value, expected, name) {
  const args = ['--yes', '--package=ajv-cli@5.0.0', '--package=ajv-formats@3.0.1', 'ajv',
    'validate', '--spec=draft2020', '--strict=true', '-c', 'ajv-formats', '-s', schema, '-d', fixture(name, value)];
  let passed = true;
  try { execFileSync('npx', args, {stdio: 'pipe'}); } catch { passed = false; }
  schemaChecks++;
  if (passed !== expected) throw new Error(`${name} ${expected ? 'should pass' : 'should fail'} schema validation`);
}
function assert(condition, message) {
  relationChecks++;
  if (!condition) throw new Error(message);
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
    dubboServiceKey: 'langchain/com.alphafrog.AgentService',
    releaseId: 'release-2',
    serviceSpecSha256: '0'.repeat(64),
    machineId: 'beta-machine-1',
    image: {repositoryDigest: `registry.local/agent@sha256:${'b'.repeat(64)}`, localImageId: `sha256:${'c'.repeat(64)}`},
    runtime: {
      containerPort: 18080,
      hostPorts: [28080, 28081],
      healthCheckProfile: 'CONTROLLER_TCP_V1',
      readinessTimeoutSeconds: 120,
      shutdownProfile: 'SPRING_BOOT_HTTP_DUBBO_V1',
      applicationDrainSeconds: 60,
      drainGraceSeconds: 60
    },
    registration: {
      serviceName: 'providers:com.alphafrog.AgentService::langchain',
      groupName: 'alphafrog-beta',
      namespaceId: 'public',
      clusterName: 'DEFAULT',
      applicationName: 'agent-langchain-service'
    },
    runtimeConfigSha256: null
  }]
};
manifest.services[0].serviceSpecSha256 = serviceDigest(manifest.services[0]);
const deploymentGenerationId = generation(manifest);
const provider = dubboProvider(manifest.services[0].dubboServiceKey);
const registration = (id, enabled) => ({
  serviceName: manifest.services[0].registration.serviceName,
  groupName: 'alphafrog-beta', namespaceId: 'public', clusterName: 'DEFAULT',
  ip: '10.0.0.8', port: 28081, nacosInstanceId: `nacos:${id}`,
  enabled, healthy: true, weight: enabled ? 1 : 0, ephemeral: true,
  metadata: {
    'alphafrog.deployment-id': manifest.deploymentId,
    'alphafrog.traffic-scope-id': 'main-beta',
    'alphafrog.release-id': 'release-2',
    'alphafrog.deployment-generation-id': deploymentGenerationId,
    'alphafrog.instance-id': id,
    zone: 'beta',
    application: manifest.services[0].registration.applicationName,
    category: 'providers',
    dynamic: 'true',
    group: provider.group,
    interface: provider.interfaceName,
    path: provider.interfaceName,
    protocol: 'tri',
    side: 'provider',
    version: provider.version
  }
});
const active = {
  instanceId: 'instance-new', machineId: 'beta-machine-1', releaseId: 'release-2',
  deploymentGenerationId, shutdownProfile: 'SPRING_BOOT_HTTP_DUBBO_V1',
  applicationDrainSeconds: 60, drainGraceSeconds: 60, manifestVersion: 2,
  serviceSpecSha256: manifest.services[0].serviceSpecSha256, containerName: 'af-instance-new',
  containerId: 'd'.repeat(64), portSlot: 'B', hostPort: 28081,
  endpoint: {address: '10.0.0.8', port: 28081}, registration: registration('instance-new', true)
};
const service = {
  serviceName: 'agent-service', dubboServiceKey: manifest.services[0].dubboServiceKey,
  phase: 'STABLE', targetManifestVersion: 2,
  targetServiceSpecSha256: manifest.services[0].serviceSpecSha256,
  activeInstance: active, candidateInstance: null, drainingInstance: null,
  operation: null, failedManifestVersion: null, lastError: null
};
const state = {
  schemaVersion: 1, stateVersion: 12, updatedAt: '2026-09-01T00:02:00Z',
  deployments: [{
    deploymentId: manifest.deploymentId, trafficScopeId: manifest.trafficScopeId, phase: 'ACTIVE',
    acceptedManifestVersion: 2, manifestSha256: digest(manifest), gitCommit: manifest.gitCommit,
    owner: manifest.owner, expiresAt: manifest.expiresAt, services: [service]
  }]
};

function relationErrors(wanted, observed) {
  const errors = [];
  const expectedGeneration = generation(wanted);
  const commonDeadline = wanted.services[0]?.runtime.applicationDrainSeconds;
  for (const spec of wanted.services) {
    if (spec.serviceSpecSha256 !== serviceDigest(spec)) errors.push('service digest');
    if (spec.registration.groupName !== 'alphafrog-beta') errors.push('beta registry group');
    const providerIdentity = dubboProvider(spec.dubboServiceKey);
    if (spec.registration.serviceName !== `providers:${providerIdentity.interfaceName}:${providerIdentity.version}:${providerIdentity.group}`)
      errors.push('Nacos service name');
    if (spec.runtime.applicationDrainSeconds !== spec.runtime.drainGraceSeconds
        || spec.runtime.applicationDrainSeconds !== commonDeadline) errors.push('common drain deadline');
  }
  for (const deployment of observed.deployments) {
    if (deployment.manifestSha256 !== digest(wanted)) errors.push('manifest digest');
    for (const item of deployment.services) {
      const spec = wanted.services.find(candidate => candidate.serviceName === item.serviceName);
      for (const [role, instance] of [['active', item.activeInstance], ['candidate', item.candidateInstance]]) {
        if (!instance) continue;
        if (instance.deploymentGenerationId !== expectedGeneration) errors.push(`${role} generation`);
        if (instance.registration.groupName !== 'alphafrog-beta') errors.push(`${role} group`);
        if (instance.registration.metadata.zone !== 'beta') errors.push(`${role} zone`);
        if (spec) {
          const identity = dubboProvider(spec.dubboServiceKey);
          const metadata = instance.registration.metadata;
          if (metadata.application !== spec.registration.applicationName
              || metadata.category !== 'providers' || metadata.dynamic !== 'true'
              || metadata.group !== identity.group || metadata.interface !== identity.interfaceName
              || metadata.path !== identity.interfaceName || metadata.protocol !== 'tri'
              || metadata.side !== 'provider' || metadata.version !== identity.version)
            errors.push(`${role} Dubbo provider metadata`);
        }
        const expectedTag = deployment.trafficScopeId === 'main-beta' ? undefined : deployment.trafficScopeId;
        if (instance.registration.metadata.tag !== expectedTag) errors.push(`${role} provider tag`);
        if (instance.registration.metadata['dubbo.tag'] !== expectedTag) errors.push(`${role} tag`);
        if (instance.applicationDrainSeconds !== commonDeadline || instance.drainGraceSeconds !== commonDeadline)
          errors.push(`${role} deadline`);
        if (spec && instance.serviceSpecSha256 !== spec.serviceSpecSha256) errors.push(`${role} service digest`);
      }
      if (item.drainingInstance) {
        if (!item.drainingInstance.registrationRemovedAt) errors.push('draining registration removal');
        if ((item.drainingInstance.stopSignalRequestedAt === null) !== (item.drainingInstance.stopDeadline === null))
          errors.push('stop time pair');
      }
    }
  }
  return errors;
}

try {
  ajv(manifestSchema, manifest, true, 'manifest-valid');
  ajv(stateSchema, state, true, 'state-valid');
  assert(relationErrors(manifest, state).length === 0, 'valid manifest and state must agree');

  const oldRetirement = clone(manifest);
  oldRetirement.services[0].runtime.preStopPolicy = 'AGENT_RETIRE_GENERATION_V1';
  ajv(manifestSchema, oldRetirement, false, 'manifest-retirement-policy-rejected');

  const wrongGroup = clone(manifest);
  wrongGroup.services[0].registration.groupName = 'DEFAULT_GROUP';
  ajv(manifestSchema, wrongGroup, false, 'manifest-production-group-rejected');

  const stableScope = clone(manifest);
  stableScope.trafficScopeId = 'stable';
  ajv(manifestSchema, stableScope, false, 'manifest-stable-scope-rejected');

  const splitDeadline = clone(manifest);
  splitDeadline.services[0].runtime.drainGraceSeconds = 65;
  assert(relationErrors(splitDeadline, state).includes('common drain deadline'), 'split deadlines must be rejected');

  const staleRoute = clone(state);
  staleRoute.deployments[0].services[0].route = {defaultInstanceId: 'instance-old'};
  ajv(stateSchema, staleRoute, false, 'state-route-fact-rejected');

  const stableStateScope = clone(state);
  stableStateScope.deployments[0].trafficScopeId = 'stable';
  ajv(stateSchema, stableStateScope, false, 'state-stable-scope-rejected');

  const wrongZone = clone(state);
  wrongZone.deployments[0].services[0].activeInstance.registration.metadata.zone = 'prod';
  ajv(stateSchema, wrongZone, false, 'state-production-zone-rejected');

  const incompleteProvider = clone(state);
  delete incompleteProvider.deployments[0].services[0].activeInstance.registration.metadata.protocol;
  ajv(stateSchema, incompleteProvider, false, 'state-provider-protocol-required');

  const wrongProvider = clone(state);
  wrongProvider.deployments[0].services[0].activeInstance.registration.metadata.interface = 'com.alphafrog.OtherService';
  assert(relationErrors(manifest, wrongProvider).includes('active Dubbo provider metadata'),
    'provider metadata must reconstruct the callable Dubbo URL');

  const laneManifest = clone(manifest);
  laneManifest.trafficScopeId = 'lane-a';
  laneManifest.services[0].serviceSpecSha256 = serviceDigest(laneManifest.services[0]);
  const laneState = clone(state);
  laneState.deployments[0].trafficScopeId = 'lane-a';
  laneState.deployments[0].manifestSha256 = digest(laneManifest);
  const laneInstance = laneState.deployments[0].services[0].activeInstance;
  laneInstance.registration.metadata['alphafrog.traffic-scope-id'] = 'lane-a';
  laneInstance.registration.metadata.tag = 'lane-a';
  laneInstance.registration.metadata['dubbo.tag'] = 'lane-a';
  laneInstance.deploymentGenerationId = generation(laneManifest);
  laneInstance.registration.metadata['alphafrog.deployment-generation-id'] = generation(laneManifest);
  assert(relationErrors(laneManifest, laneState).length === 0, 'lane registration must carry its static Dubbo tag');

  console.log(`Beta contract verification passed: ${schemaChecks} schema checks, ${relationChecks} relation checks.`);
} finally {
  fs.rmSync(temp, {recursive: true, force: true});
}
