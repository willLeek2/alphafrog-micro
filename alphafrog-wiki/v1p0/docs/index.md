# AlphaFrog v1.0 Agent Docs

This MkDocs site is the v1.0 reference surface for Agent Run client scripts and operational debugging.

Current pages:

- [Agent Run HTTP Endpoints](agent-run-endpoints.md): login, run lifecycle, SSE, status/result/cost, observability, snapshot parts, artifacts, export, feedback, and follow-up messages.

Source-of-truth files used for the current endpoint page:

- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/controller/AuthController.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/controller/agent/AgentController.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/controller/agent/AgentSseController.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/service/AgentSseService.java`
- `frontend/src/main/java/world/willfrog/alphafrogmicro/frontend/model/agent/*.java`
- `test_scripts/agent-v1p0/agent_run_sse_load_test.py`
- `test_scripts/agent-v1p0/froglib/flow_client.py`
