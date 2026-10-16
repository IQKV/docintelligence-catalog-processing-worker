# Repository Guidelines & Agent Instructions

## Overview

This document provides comprehensive guidelines for repository management, development workflows, and collaboration standards for Maven-based Java projects. It serves as a reference for both human developers and AI agents working with this codebase.

## 🏛️ Repository Structure & Organization

### Tactical DDD Project Layout

```
project-root/
├── .github/                          # GitHub workflows and automation
│   └── workflows/                    # CI/CD pipeline definitions
├── src/
│   ├── main/
│   │   ├── java/                     # Java source code
│   │   │   └── com/example/project/
│   │   │       ├── shared/           # Shared kernel (cross-cutting concerns)
│   │   │       │   ├── domain/       # Shared domain primitives
│   │   │       │   ├── exception/    # Common exceptions
│   │   │       │   └── util/         # Utility classes
│   │   │       ├── infrastructure/   # Infrastructure layer
│   │   │       │   ├── config/       # Spring configuration
│   │   │       │   ├── security/     # Security implementation
│   │   │       │   ├── persistence/  # Repositories, adapters
│   │   │       │   └── messaging/    # Event publishers, message brokers
│   │   │       ├── [bounded-context-1]/  # Example: user
│   │   │       │   ├── domain/       # Domain layer (core business logic)
│   │   │       │   │   ├── model/    # Aggregates, entities, value objects
│   │   │       │   │   ├── service/  # Domain services
│   │   │       │   │   └── event/    # Domain events
│   │   │       │   ├── application/  # Application layer
│   │   │       │   │   ├── service/  # Application services (use cases)
│   │   │       │   │   ├── dto/      # Data transfer objects
│   │   │       │   │   └── port/     # Ports (interfaces for adapters)
│   │   │       │   └── adapter/      # Adapters (interface layer)
│   │   │       │       ├── in/       # Inbound adapters
│   │   │       │       │   └── rest/ # REST controllers
│   │   │       │       └── out/      # Outbound adapters
│   │   │       │           └── persistence/ # Repository implementations
│   │   │       ├── [bounded-context-2]/  # Example: order
│   │   │       │   ├── domain/
│   │   │       │   ├── application/
│   │   │       │   └── adapter/
│   │   │       └── Application.java  # Spring Boot main class
│   │   └── resources/
│   │       ├── application.yml       # Base configuration
│   │       ├── application-local.yml # Local development profile
│   │       └── db/changelog/         # Database migrations (if using Liquibase)
│   └── test/
│       └── java/                     # Unit, integration, and architecture tests
│           └── com/example/project/
│               ├── [bounded-context]/
│               │   ├── domain/       # Domain model tests
│               │   ├── application/  # Application service tests
│               │   └── adapter/      # Adapter tests
│               └── architecture/     # ArchUnit tests
├── .gitignore
├── pom.xml                           # Maven build configuration
├── README.md
└── AGENTS.md                         # This file
```

**Key DDD Concepts:**

- **Bounded Context**: Logical boundary for a specific domain model (e.g., user, order, payment)
- **Domain Layer**: Core business logic, entities, value objects, aggregates, domain services
- **Application Layer**: Use cases, orchestration, DTOs, ports (interfaces)
- **Adapter Layer**: Implementation of ports (REST controllers, repository implementations)
- **Infrastructure**: Technical concerns (config, security, persistence framework)
- **Shared Kernel**: Common code shared across bounded contexts

## Execution Discipline

- Root cause first. Fix the real entry point, not a bypass around it.
- Read complete affected modules, callers, and tests before editing.
- After two identical failures without new evidence, change approach — do not retry blindly.
- Check relevant prerequisites early (`./mvnw verify`). Parallelize independent work.
- Behavior proven and required gates green: finish. No speculative scope growth.

## Minimal Change Discipline

Solve the whole problem with the least new code. The best code is the code never written.

### Scope before you write

Read the task and the code it touches. List every place the change must reach: callers, tests, fixtures, config, exports. Check what it could break for users: data it would destroy or expose, callers that stop working. That is scope. Extra features are not.

### Pick the smallest complete change

Take the first option that fully works:

1. Does it need to exist? Drop features, options and flexibility nobody asked for — name them in one line so the user can decide.
2. Already in this codebase (helper, component, service, pattern)? Use it the way the surrounding code does.
3. Standard library or platform feature? Use it, unless the project has its own.
4. An installed dependency? Use it. Never add a dependency for a few lines.
5. Can it be one line a reader gets at a glance? One line.
6. Otherwise: the minimum code that works.

Be lazy about the solution, never about the change itself:

- Finish every part the task needs, including the callers, tests and fixtures the change breaks.
- No abstraction, wrapper, option, config, boilerplate or "for later" code nobody asked for. Keep values in the form the platform already gives you. Deletion beats addition. Keep the layers, interfaces and conventions the codebase already has.
- The shortest working diff wins, once you know everything it must touch. A one-liner that needs decoding is not short.
- Comment only the why the code cannot show, in one line.
- Bug fix: grep every caller of the function you touch, then fix the root cause once in the shared code.
- Code you move or merge keeps its error handling and validation.
- Between options of equal size, take the one that is correct on edge cases.
- New non-trivial logic (a branch, a loop, a parser, money or security, or a whole new script) leaves one small test or an assert-based self-check. Trivial changes need none.
- A shortcut with a known limit gets a one-line comment: `shortcut: <the limit>, <when to upgrade>`.
- Push back when the need does not justify a part: state the smaller option in one line instead of building the bigger one silently.

Never cut: validation at trust boundaries, error handling that prevents data loss, security, accessibility, the calibration real hardware needs, anything the user explicitly asked for.

### Token discipline

- Locate with Grep/Glob, then read only the ranges you need — not whole trees or whole files.
- Prefer editing existing files to creating new ones; reuse code that is already there before writing.
- Never paste file contents back in a reply. Cite the path and lines.
- Keep replies to the outcome: what changed, what passed, any risk (see AI Communication Standards).

## Security

- Keep credentials, tokens, and private config out of commits, logs, and shared text.
- Flag files likely to contain secrets (`.env`, `application-local.yml`) before staging.
- No hardcoded secrets — use environment variables or Spring config properties.
- Use exact or pinned dependency versions. Flag unusual package names before installing.
- Never bypass `--no-verify` unless explicitly requested.

## Commit Standards

Format: `type(scope): subject`

- Subject: imperative, lowercase, no trailing period, ≤ 72 chars
- Types: `feat`, `fix`, `improvement`, `refactor`, `docs`, `test`, `chore`, `ci`, `perf`, `revert`
- Scope: affected bounded context or layer — replace `aichat` with your actual service name
- For `fix`: describe the symptom and trigger, not the code change
    - ✅ `fix(security): unauthenticated requests bypass tenant guard in single-tenant mode`
    - ❌ `fix(security): add missing auth check`

Examples:

- `feat(domain): add soft-delete support to aggregate root`
- `fix(messaging): consumer silently drops events with unknown tenant key`
- `refactor(infrastructure): extract CORS config into dedicated properties class`
- `chore(deps): update spring-boot to 3.5.0`

## 🤖 AI Agent Guidelines

### AI Communication Standards

**CRITICAL: Agents must communicate concisely and avoid unnecessary verbosity.**

#### Response Length Guidelines

```yaml
summaries:
    max_length: "2-3 sentences"
    focus: "What was done, not how it was done"
    avoid: "Bullet point lists, detailed recaps, obvious statements"

explanations:
    when_detailed: "Complex technical concepts, architecture decisions, security implications"
    when_brief: "Simple changes, routine operations, status updates"

verification:
    format: "Minimal wording - state outcome only"
    example: "Tests pass. Coverage at 65%."
    avoid: "Lengthy descriptions of what was verified"
```

#### Prohibited Outputs

**NEVER create these files:**

- ❌ `SUMMARY.md` - Auto-generated work summaries
- ❌ `CHANGES.md` - Change documentation (use commit messages)
- ❌ `REVIEW.md` - Code review summaries
- ❌ `ANALYSIS.md` - Analysis reports (communicate directly)
- ❌ Any markdown file documenting agent work unless explicitly requested

**Why:** These files create noise, clutter the repository, and provide no value. Use commit messages and direct communication instead.

#### Concise vs Verbose Examples

**❌ VERBOSE (Don't do this):**

```
I have successfully completed the implementation of the new user authentication
endpoint. Here's a comprehensive summary of everything I did:

• Created a new UserAuthenticationController class
• Implemented the login method with proper validation
• Added JWT token generation logic
• Created comprehensive unit tests
• Updated the OpenAPI documentation

All tests are passing successfully and the code is ready for review.
```

**✅ CONCISE (Do this):**

```
Added user authentication endpoint with JWT token generation. Tests pass, coverage at 68%.
```

#### When to Be Detailed

Provide detailed explanations ONLY for:

1. **Complex Architecture Decisions**
2. **Security Implications**
3. **Breaking Changes**
4. **Non-Obvious Technical Choices**

#### Response Templates

**For Simple Changes:**

```
Changed X to Y. Tests pass.
```

**For Bug Fixes:**

```
Fixed [issue]. Root cause: [brief explanation]. Added regression test.
```

**For New Features:**

```
Implemented [feature]. Includes [key components]. Tests pass, coverage [X]%.
```

**For Refactoring:**

```
Refactored [component] to [improvement]. No behavior changes. Tests pass.
```

#### Communication Principles

1. **Action-Oriented**: Focus on what was done, not the process
2. **Results-First**: State the outcome immediately
3. **No Redundancy**: Don't repeat what's obvious from the code
4. **No Meta-Commentary**: Don't describe your own actions
5. **Trust the User**: They can read code; don't explain obvious changes
6. **Verification is Brief**: "Tests pass" is sufficient

### Technology Stack Context

Before making recommendations, agents should understand the project's technology stack:

**Runtime & Framework**

- Java 25 with modern features (records, pattern matching, text blocks, var)
- Spring Boot 4.x
- Maven for build management

**Data & Persistence**

- Database: PostgreSQL (production), H2 (test scope — smoke context-load only)
- **MyBatis** with XML mappers — there is no Spring Data JPA / Hibernate in this project
- `mybatis.configuration.map-underscore-to-camel-case=true` is enabled globally
- Mapper interfaces annotated with `@Mapper`; XML mapper files in `src/main/resources/mappers/<context>/`
- Liquibase for schema migrations (`db/changelog/system/db.changelog-system.xml`)
- **ShedLock** (`shedlock-spring` + `shedlock-provider-jdbc-template`) for distributed scheduled job locking

**Security**

- Spring Security
- JWT authentication (if configured)
- Method-level security with `@PreAuthorize`

**Testing**

- JUnit 5 for unit tests
- Mockito for mocking
- Testcontainers for integration tests (if configured)
- ArchUnit for architecture validation

**API Documentation**

- SpringDoc OpenAPI 3
- Swagger UI

**AI**

- Spring AI (`spring-ai-starter-model-ollama`) — Ollama-backed chat completions
- Chat sessions and messages stored in `public.chat_sessions` / `public.chat_messages`

**Build & Deployment**

- Maven 3.9.0+
- Docker (Dockerfile present)
- Environment profiles: `local`, `sit`, `uat`, `prd`

## 📋 Development Standards

### Branch Strategy

> **This is a GitHub template.** The strategy below is the default used in this repository. When you instantiate the template, replace it with whatever model fits your team — Gitflow, GitHub Flow, environment-branch promotion, or something else entirely. The branch names, CI triggers, and protection rules in this file and in `.github/workflows/` all need to match your chosen model.
>
> Common alternatives:
>
> - **GitHub Flow** — single `main` branch, short-lived feature branches, deploy on merge
> - **Gitflow** — `main` + `develop`, `release/*` branches, hotfixes from `main`
> - **Environment branches** — `dev` → `staging` → `main`, promotion via PRs or CI

This template ships with **scaled trunk-based development**:

- `dev` is the **trunk** — the single long-lived branch, always kept stable and deployable
- All work is done on short-lived branches that merge into `dev` via pull request
- Releases are cut directly from `dev` as **annotated tags** (e.g. `v1.2.0`) — there is no separate release branch
- There is no `main` or `develop` branch; `dev` serves both roles

```
dev  ← trunk (stable, always green)
├── feature/*      (new features)
├── bugfix/*       (bug fixes)
├── improvement/*  (enhancements)
├── hotfix/*       (urgent production fixes — merged to dev, tagged immediately)
└── rfc/*          (request for comments / architectural proposals)
```

**Release flow:**

```bash
# Cut a release from dev
git tag -a v1.2.0 -m "chore(release): v1.2.0"
git push origin v1.2.0
```

### Branch Naming Conventions

```bash
# Feature branches
feature/add-user-authentication
feature/implement-caching

# Bug fixes
bugfix/fix-null-pointer-exception
bugfix/resolve-connection-leak

# Improvements
improvement/optimize-database-queries
improvement/enhance-error-messages

# Hotfixes
hotfix/critical-security-patch

# RFC (Request for Comments)
rfc/new-authentication-flow
```

### Commit Message Format (Conventional Commits)

**Format:**

```
type(scope): subject

[optional body]

[optional footer]
```

**Allowed Types:**

- `feat`: New feature
- `fix`: Bug fix
- `rfc`: Request for comments / architectural proposal
- `docs`: Documentation changes
- `style`: Code style changes
- `improvement`: Enhancements to existing features
- `refactor`: Code refactoring
- `perf`: Performance improvements
- `test`: Adding or updating tests
- `chore`: Maintenance tasks
- `build`: Build system changes
- `ci`: CI/CD pipeline changes
- `revert`: Reverting previous commits

**Rules:**

- Subject line: 6-220 characters
- Use lowercase for type
- Use imperative mood ("add" not "added")
- No period at end of subject line

**Examples:**

```bash
feat(auth): add JWT authentication endpoint

fix(user): resolve null pointer in user service

The service was not checking for null values before
accessing user properties.

Closes #123

perf(database): optimize user search query with indexes

docs(readme): update installation instructions

refactor(service): extract validation logic to separate class
```

### AI Commit Message Generation

**When AI Should Generate Commit Messages:**

AI agents should automatically generate and present commit messages after:

- Completing multi-file changes (3+ files modified)
- Implementing new features or bug fixes
- Performing refactoring across multiple components
- Making configuration or infrastructure changes

**AI Workflow for Commit Message Generation:**

```yaml
after_completing_changes:
    1. analyze_changes: "Review all modified files and understand the scope"
    2. identify_type: "Determine the appropriate commit type"
    3. determine_scope: "Identify the affected component"
    4. craft_subject: "Write concise subject line (6-220 chars)"
    5. add_body_if_needed: "Include body for complex changes"
    6. present_to_user: "Show the generated commit message for review"
    7. wait_for_approval: "User can accept, modify, or reject"
```

**Example Presentation:**

```
I've completed the changes. Here's the suggested commit message:

---
feat(auth): add JWT authentication endpoint

Implements JWT-based authentication with token generation
and validation. Includes rate limiting and comprehensive tests.
---

Would you like me to use this commit message, or would you prefer to modify it?
```

### Code Quality Standards

#### Java Code Standards (Java 25)

**Use Modern Java Features:**

```java
// Records for immutable DTOs
public record UserDto(@NotBlank String username, @Email String email, Instant createdAt) {}

// Pattern matching with switch expressions
public String getUserRole(User user) {
  return switch (user.getRole()) {
    case ADMIN -> "Administrator";
    case USER -> "Regular User";
    default -> "Unknown";
  };
}

// Text blocks for multi-line strings
var query = """
  SELECT u FROM User u
  WHERE u.email = :email
  AND u.enabled = true
  """;

// var for local variables (when type is obvious)
var users = userRepository.findAll();
```

**Import Order (Checkstyle Enforced):**

```java
// 1. Static imports (alphabetically sorted)
import static org.assertj.core.api.Assertions.assertThat;

// 2. Standard Java/Jakarta packages (alphabetically sorted)
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
// 3. Third-party packages (alphabetically sorted)
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;
```

**Import Rules:**

```yaml
import_formatting:
    - "Separate each group with a blank line"
    - "Sort imports alphabetically within each group"
    - "No wildcard imports (import java.util.*) - use explicit imports"
    - "No unused imports"
    - "Package and import statements must not be line-wrapped"

checkstyle_modules:
    AvoidStarImport:
        description: "Prohibits wildcard imports (import java.util.*)"
        enforcement: "Build fails on star imports"
        rationale: "Explicit imports improve code clarity and prevent naming conflicts"

    UnusedImports:
        description: "Detects and removes unused import statements"
        enforcement: "Build fails on unused imports"
```

**Service Layer Pattern:**

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

  private final UserRepository userRepository;

  @Transactional
  public UserDto createUser(UserCreateCommand command) {
    log.info("Creating user: {}", command.username());

    var user = User.builder().username(command.username()).email(command.email()).build();

    var savedUser = userRepository.save(user);
    return UserMapper.toDto(savedUser);
  }
}
```

**REST Controller Pattern:**

```java
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User Management")
public class UserController {

  private final UserService userService;

  @GetMapping("/{id}")
  @Operation(summary = "Get user by ID")
  public ResponseEntity<UserDto> getUser(@PathVariable Long id) {
    return userService.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
  }
}
```

#### Testing Standards

**Unit Tests - AAA Pattern:**

Use `@ExtendWith(MockitoExtension.class)`. Inject all dependencies via constructor — **do not use `@InjectMocks`**. Instantiate the class under test manually in `@BeforeEach`.

```java
@ExtendWith(MockitoExtension.class)
class ChatServiceImplTest {

  @Mock
  private ChatSessionMapper chatSessionMapper;
  @Mock
  private ChatMessageMapper chatMessageMapper;

  private ChatServiceImpl chatService;

  @BeforeEach
  void setUp() {
    chatService = new ChatServiceImpl(chatSessionMapper, chatMessageMapper);
  }

  @Test
  @DisplayName("Should throw when session not found")
  void shouldThrowWhenSessionNotFound() {
    // Arrange
    final var id = UUID.randomUUID();
    when(chatSessionMapper.findById(id)).thenReturn(Optional.empty());

    // Act & Assert
    assertThatThrownBy(() -> chatService.getSession(id))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
```

**Integration Tests with Testcontainers:**

```java
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class UserServiceIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private UserService userService;

  @Test
  void shouldCreateAndRetrieveUser() {
    // Test implementation
  }
}
```

**Architecture Tests with ArchUnit:**

```java
@AnalyzeClasses(packagesOf = AiChatServiceApplication.class, importOptions = DoNotIncludeTests.class)
class TechnicalStructureTest {

  @ArchTest
  static final ArchRule respectsTechnicalArchitectureLayers = onionArchitecture()
      .withOptionalLayers(true)
      .ignoreDependency(belongToAnyOf(AiChatServiceApplication.class), alwaysTrue());
}
```

### Maven Command Best Practices for AI Agents

**STRICT RECOMMENDATION: Always use `-Dcheckstyle.skip=true` when running Maven commands during development.**

```yaml
maven_commands:
    development_phase:
        recommended: "mvn clean verify -Dcheckstyle.skip=true"
        reason: "Focus on functionality and tests without style blocking"

    testing_phase:
        recommended: "mvn test -Dcheckstyle.skip=true"
        reason: "Rapid test iteration without style checks"

    style_check_phase:
        explicit: "mvn checkstyle:check"
        when: "Before committing or when explicitly requested"

workflow:
    1. develop: "Implement features with -Dcheckstyle.skip=true"
    2. test: "Run tests with -Dcheckstyle.skip=true"
    3. verify: "Ensure functionality works correctly"
    4. style: "Run mvn checkstyle:check separately"
    5. fix_style: "Address Checkstyle violations in focused pass"
    6. commit: "CI/CD enforces Checkstyle automatically"

rationale:
    - "Checkstyle violations should not block functional development"
    - "Style issues are better addressed in dedicated cleanup phase"
    - "CI/CD pipeline enforces style checks before merge"
    - "Faster iteration cycle for AI-assisted development"
```

**Example Commands:**

```bash
# ✅ RECOMMENDED: Development and testing
mvn clean verify -Dcheckstyle.skip=true
mvn test -Dcheckstyle.skip=true
mvn clean install -Dcheckstyle.skip=true

# ✅ RECOMMENDED: Explicit style check when ready
mvn checkstyle:check

# ❌ NOT RECOMMENDED: Running verify without skip during active development
mvn clean verify  # May fail due to style issues, blocking progress
```

## 🔄 Workflow Management

### Pull Request Guidelines

#### PR Title Format

PR titles must follow Conventional Commits format:

```
type(scope): description

Examples:
feat(auth): add JWT authentication
fix(user): resolve null pointer exception
docs(readme): update installation guide
refactor(service): extract validation logic
```

#### PR Description Template

```markdown
## Description

Brief description of changes and motivation.

## Type of Change

- [ ] Bug fix
- [ ] New feature
- [ ] Breaking change
- [ ] Documentation update
- [ ] Performance improvement
- [ ] Refactoring

## Changes Made

- List specific changes
- Include affected components

## How Has This Been Tested?

- [ ] Unit tests added/updated
- [ ] Integration tests added/updated
- [ ] Manual testing performed

## Test Coverage

- Current coverage: X%
- Coverage change: +/-X%

## Checklist

- [ ] Code follows style guidelines
- [ ] Commit messages follow Conventional Commits
- [ ] Self-review completed
- [ ] Tests cover new/modified code
- [ ] All tests pass locally
- [ ] Documentation updated

## Security Considerations

- [ ] No sensitive data in logs
- [ ] Input validation implemented
- [ ] Authorization checks in place

## Additional Notes
```

#### Review Criteria

**Automated Checks (Must Pass):**

- ✅ All tests pass
- ✅ Checkstyle validation passes
- ✅ Code coverage meets minimum threshold
- ✅ Commit messages follow Conventional Commits
- ✅ No merge conflicts

**Manual Review Focus:**

```yaml
review_checklist:
    code_quality:
        - Modern Java features used appropriately
        - Proper exception handling
        - No code duplication

    security:
        - Input validation with @Valid
        - No SQL injection vulnerabilities
        - No sensitive data exposure

    testing:
        - AAA pattern in unit tests
        - Edge cases covered
        - Integration tests for critical paths

    documentation:
        - OpenAPI annotations on endpoints
        - Complex logic documented
        - README updated if needed
```

## 🔒 Security Guidelines

### Security Checklist

```yaml
authentication:
  - [ ] Secure authentication mechanism implemented
  - [ ] Password hashing (BCrypt or similar)
  - [ ] Token expiration configured
  - [ ] Account lockout protection

authorization:
  - [ ] Role-based access control
  - [ ] Method-level security with @PreAuthorize
  - [ ] User can only access own data

input_validation:
  - [ ] Bean Validation annotations (@Valid, @NotBlank)
  - [ ] SQL injection prevention (parameterized queries)
  - [ ] XSS prevention
  - [ ] Request size limits configured

data_protection:
  - [ ] Passwords never logged
  - [ ] Sensitive data encrypted
  - [ ] HTTPS enforced in production
  - [ ] Secure headers configured

secrets_management:
  - [ ] No hardcoded secrets
  - [ ] Environment variables for configuration
  - [ ] .env files gitignored
```

### Security Testing

```java
@Test
void shouldRejectInvalidToken() {
  var invalidToken = "invalid.token";
  assertThrows(AuthenticationException.class, () -> authService.validateToken(invalidToken));
}

@Test
@WithMockUser(roles = "USER")
void shouldDenyAccessToAdminEndpoint() throws Exception {
  mockMvc.perform(get("/api/v1/iam/admin/users")).andExpect(status().isForbidden());
}
```

## 📊 Quality Assurance

### Code Quality Metrics

```yaml
test_coverage:
    minimum_threshold: ">= 60%"
    target: ">= 80%"

code_style:
    tool: "Checkstyle"
    enforcement: "Maven build fails on violations"

architecture:
    tool: "ArchUnit"
    rules:
        - "Services only accessed by controllers"
        - "No cyclic dependencies"
        - "Proper package structure"
```

### Quality Gates

```bash
# Run all quality checks locally
mvn clean verify

# Run only tests
mvn test

# Check code coverage
mvn jacoco:report
# View: target/site/jacoco/index.html

# Check code style
mvn checkstyle:check

# Run architecture tests
mvn test -Dtest=*ArchitectureTest
```

## 🚀 CI/CD Pipeline

### Recommended GitHub Actions Workflow

```yaml
name: CI/CD Pipeline

on:
    push:
        branches: [dev]
        tags: ["v*.*.*"]
    pull_request:
        branches: [dev]

jobs:
    build-and-test:
        runs-on: ubuntu-latest

        steps:
            - uses: actions/checkout@v4

            - name: Set up JDK 25
              uses: actions/setup-java@v4
              with:
                  java-version: "25"
                  distribution: "temurin"
                  cache: "maven"

            - name: Build and Test
              run: mvn clean verify

            - name: Upload Coverage
              uses: codecov/codecov-action@v4
              with:
                  files: target/site/jacoco/jacoco.xml
```

## 📚 Documentation Standards

### API Documentation with OpenAPI

```java
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "User Management", description = "User operations")
public class UserController {

  @GetMapping("/{id}")
  @Operation(summary = "Get user by ID", description = "Retrieves a user by their unique identifier")
  @ApiResponses({ @ApiResponse(responseCode = "200", description = "User found"), @ApiResponse(responseCode = "404", description = "User not found") })
  public ResponseEntity<UserDto> getUser(@Parameter(description = "User ID", example = "1") @PathVariable Long id) {
    return userService.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
  }
}
```

### Code Documentation

**When to Document:**

- Complex business logic
- Non-obvious algorithms
- Security-critical code
- Public APIs

**When NOT to Document:**

- Self-explanatory code
- Simple getters/setters
- Obvious implementations

## 🎯 Agent Decision Framework

### User Confirmation Policy

**CRITICAL RULE: Always ask for user confirmation before applying changes.**

```yaml
before_making_changes:
    1. analyze: "Understand the request and identify required changes"
    2. explain: "Describe what changes will be made and why"
    3. assess_impact: "Evaluate impact, effort, and risk"
    4. present_options: "Offer alternatives if applicable"
    5. wait_for_approval: "STOP and wait for explicit user confirmation"
    6. apply_changes: "Only after user approves"
    7. verify: "Confirm changes work as expected"

exceptions:
    - read_only_operations: "Reading files, searching, analyzing"
    - information_requests: "Answering questions, explaining concepts"
    - recommendations: "Suggesting approaches without implementing"

never_auto_apply:
    - code_changes: "Any modification to source files"
    - configuration_changes: "application.yml, pom.xml, etc."
    - dependency_updates: "Adding or updating dependencies"
    - refactoring: "Code restructuring"
    - deletions: "Removing files or code"
    - security_changes: "Authentication, authorization, secrets"
```

### When to Intervene

**High Priority (Immediate Action - Still Requires Approval):**

- 🚨 Security vulnerabilities
- 🔴 Build failures blocking development
- 💥 Critical bugs affecting core functionality
- ⚠️ Authentication/authorization failures

**Medium Priority (Plan and Execute - Requires Approval):**

- 📉 Code quality degradation
- 🐌 Performance regressions
- ❌ Test failures
- 📦 Dependency updates

**Low Priority (Continuous Improvement - Requires Approval):**

- 📝 Documentation gaps
- ♻️ Refactoring opportunities
- 🎨 Code style improvements
- 🧪 Test coverage improvements

### Decision Matrix

```yaml
impact_assessment:
    critical: "Service unavailable, data loss risk"
    high: "Major feature broken, workaround exists"
    medium: "Minor feature affected"
    low: "Internal improvement, no user impact"

effort_estimation:
    small: "< 1 day"
    medium: "1-3 days"
    large: "1-2 weeks"
    xlarge: "> 2 weeks"

risk_evaluation:
    low: "Well-understood change, easy rollback"
    medium: "New pattern, tested rollback"
    high: "Complex change, difficult rollback"
    critical: "Breaking change, irreversible"
```

### Common Patterns and Solutions

**Pattern: Adding a new REST endpoint**

```java
// 1. Define DTO
public record CreateRequest(@NotBlank String name) {}

// 2. Add service method
@Service
public class MyService {

  public MyDto create(CreateRequest request) {
    /* ... */
  }
}

// 3. Add controller endpoint
@RestController
public class MyController {

  @PostMapping
  @Operation(summary = "Create resource")
  public ResponseEntity<MyDto> create(@Valid @RequestBody CreateRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(myService.create(request));
  }
}

// 4. Add tests
@Test
void shouldCreateResource() {
  /* ... */
}
```

**Pattern: Adding database migration (Liquibase)**

```xml
<changeSet id="001" author="developer">
    <createTable tableName="users">
        <column name="id" type="bigint" autoIncrement="true">
            <constraints primaryKey="true"/>
        </column>
        <column name="username" type="varchar(100)">
            <constraints nullable="false" unique="true"/>
        </column>
        <column name="created_at" type="timestamp"
                defaultValueComputed="CURRENT_TIMESTAMP"/>
    </createTable>
</changeSet>
```

**Pattern: Adding caching**

```java
@Service
public class MyService {

  @Cacheable(value = "items", key = "#id")
  public Optional<ItemDto> findById(Long id) {
    return repository.findById(id).map(ItemMapper::toDto);
  }

  @CacheEvict(value = "items", key = "#id")
  public void delete(Long id) {
    repository.deleteById(id);
  }
}
```

---

This document serves as a comprehensive reference for maintaining high-quality, secure, and well-organized Java projects while facilitating effective collaboration between human developers and AI agents.
