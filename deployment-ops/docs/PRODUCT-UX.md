# Product usability update

## Changes

- Navigation grouped into Workspace, Infrastructure and Administration, with page descriptions and persistent active state. URL fragments support direct links and browser history.
- Overview uses real node health, clickable counts and common-task shortcuts.
- Applications are grouped by project and node. Retained versions do not make an otherwise running managed application appear partially stopped.
- Long lists support search, 15-row pages and result counts. Small summary tables avoid unnecessary pagination controls.
- Images, nodes, links and users use labeled form dialogs rather than permanent form clutter.
- Container details and live CPU/memory/network counters have readable summaries. Raw payloads remain under Technical details. Logs use a dedicated viewer.
- Container mutation controls match running state and account role. System-container mutations are hidden. Changes require a named confirmation with Cancel focused first.
- A guided deployment form selects actual local images, collects ports and health configuration, checks known port conflicts, then presents a review before the separate deployment action. JSON import remains an advanced option. No persistent-volume support is implied.
- Registry unavailability becomes a helpful optional-service state.
- Read-only accounts have no management forms or mutation buttons. Server permissions remain authoritative.
- Local English assets, desktop/mobile layout, focus outlines, named dialogs, loading indicators, operation feedback and retry states.

## Validation

Seven executor tests and frontend/document checks passed. Real Chrome browser checks covered navigation, application search and empty states, project-to-container filtering, cancellation of a container action, readable details, image pagination and import dialog, guided deployment review/back/cancel, node health, optional Registry state, mobile overflow and sign out.

Browser deployment checks stop at Review: they never submit an application deployment or container mutation. Permission checks use the dedicated acceptance_viewer account and a rejected write request. They do not change existing application containers.

The visual and guided workflow changes do not resolve the backend feature gaps listed in KNOWN-ISSUES.md. In particular, stateful applications, complete Registry management and physical multi-node acceptance remain separate work.
