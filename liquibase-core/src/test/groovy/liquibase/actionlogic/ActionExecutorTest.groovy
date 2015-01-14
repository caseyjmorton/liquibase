package liquibase.actionlogic

import liquibase.Scope
import liquibase.action.*
import liquibase.exception.ActionPerformException
import liquibase.exception.ValidationErrors
import liquibase.test.JUnitResourceAccessor
import spock.lang.Specification

class ActionExecutorTest extends Specification {

    Scope scope;

    def setup() {
        scope = new Scope(new JUnitResourceAccessor(), ["liquibase.actionlogic.ActionLogicFactory": new ActionLogicFactory(new Scope(new JUnitResourceAccessor(), new HashMap<String, Object>())) {
            @Override
            protected Class<? extends ActionLogic>[] getActionLogicClasses() {
                return new Class[0];
            }

            @Override
            protected TemplateActionLogic[] getTemplateActionLogic(Scope scope) {
                return new TemplateActionLogic[0];
            }
        }])
    }

    def "execute when null actionLogic"() {
        when:
        new ActionExecutor().execute(new MockAction(), scope) == null

        then:
        def e = thrown(ActionPerformException)
        e.message == "No supported ActionLogic implementation found for 'mock()'"
    }

    def "execute when validation fails with errors"() {
        when:
        scope.getSingleton(ActionLogicFactory).register(new MockActionLogic("mock logic", 1, MockAction) {
            @Override
            ValidationErrors validate(Action action, Scope scope) {
                return new ValidationErrors()
                        .addError("Mock Validation Error")
                        .addError("Another Error")
            }
        })

        new ActionExecutor().execute(new MockAction(), scope)

        then:
        def e = thrown(ActionPerformException)
        e.message == "Validation Error(s): Mock Validation Error; Another Error"
    }

    def "execute update logic"() {
        when:
        scope.getSingleton(ActionLogicFactory).register(new MockActionLogic("mock logic", 1, MockAction, {
            return new UpdateResult(12, "update logic ran");
        }))

        def result = new ActionExecutor().execute(new MockAction(), scope)

        then:
        result instanceof UpdateResult
        result.message == "update logic ran"
        ((UpdateResult) result).numberAffected == 12
    }

    def "execute 'execute' logic"() {
        when:
        scope.getSingleton(ActionLogicFactory).register(new MockActionLogic("mock logic", 1, MockAction, {
            return new ExecuteResult("execute logic ran");
        }))

        def result = new ActionExecutor().execute(new MockAction(), scope)

        then:
        result instanceof ExecuteResult
        result.message == "execute logic ran"
    }

    def "execute 'query' logic"() {
        when:
        scope.getSingleton(ActionLogicFactory).register(new MockActionLogic("mock logic", 1, MockAction, {
            return new RowBasedQueryResult("DATA", "query logic ran");
        }))

        def result = new ActionExecutor().execute(new MockAction(), scope)

        then:
        result instanceof QueryResult
        result.message == "query logic ran"
    }

    def "execute 'rewrite' logic with an empty rewrite action list throws an exception"() {
        when:
        def factory = scope.getSingleton(ActionLogicFactory)
        factory.register(new MockActionLogic("mock logic", 1, MockAction, {
            return new RewriteResult();
        }))

        def result = new ActionExecutor().execute(new MockAction(), scope)

        then:
        def e = thrown(ActionPerformException)
        e.message == "liquibase.actionlogic.MockActionLogic tried to handle 'mock()' but returned no actions to run"
    }

    def "execute 'rewrite' logic with a single rewrite action"() {
        when:
        def factory = scope.getSingleton(ActionLogicFactory)
        factory.register(new MockActionLogic("mock logic", 1, MockAction, {
            return new RewriteResult(new UpdateSqlAction("sql action 1"));
        }))
        factory.register(new MockActionLogic("mock sql", 1, UpdateSqlAction) {
            @Override
            ActionResult execute(Action action, Scope scope) throws ActionPerformException {
                return new ExecuteResult("executed sql: " + ((AbstractSqlAction) action).getAttribute(AbstractSqlAction.Attr.sql, String));
            }
        })

        then:
        def result = new ActionExecutor().execute(new MockAction(), scope)

        result instanceof ExecuteResult
        result.message == "executed sql: sql action 1"
    }

    def "execute 'rewrite' logic with multiple rewrite actions"() {
        when:
        def factory = scope.getSingleton(ActionLogicFactory)
        factory.register(new MockActionLogic("mock logic", 1, MockAction, {
            return new RewriteResult(new UpdateSqlAction("sql action 1"), new UpdateSqlAction("sql action 2"));
        }))
        factory.register(new MockActionLogic("mock sql", 1, UpdateSqlAction) {
            @Override
            ActionResult execute(Action action, Scope scope) throws ActionPerformException {
                return new ExecuteResult("executed sql: " + ((AbstractSqlAction) action).getAttribute(AbstractSqlAction.Attr.sql, String));
            }
        })

        then:
        def result = new ActionExecutor().execute(new MockAction(), scope)

        def nestedActions = new ArrayList<Map.Entry>(((CompoundResult) result).resultsBySource.entrySet())

        nestedActions.size() == 2
        nestedActions[0].key == new UpdateSqlAction("sql action 1")
        nestedActions[0].value.message == "executed sql: sql action 1"

        nestedActions[1].key == (new UpdateSqlAction("sql action 2"))
        nestedActions[1].value.message == "executed sql: sql action 2"
    }

    def "execute 'rewrite' logic with multiple levels of rewrite actions"() {
        when:
        def factory = scope.getSingleton(ActionLogicFactory)
        factory.register(new MockActionLogic("mock logic", 1, MockAction, {
            return new RewriteResult(new UpdateSqlAction("sql action 1"), new ExecuteSqlAction("exec sql action"), new UpdateSqlAction("sql action 2"));
        }))
        factory.register(new MockActionLogic("mock sql", 1, UpdateSqlAction) {
            @Override
            ActionResult execute(Action action, Scope scope) throws ActionPerformException {
                return new ExecuteResult("executed sql: " + ((AbstractSqlAction) action).getAttribute(AbstractSqlAction.Attr.sql, String));
            }
        })

        factory.register(new MockActionLogic("mock execute sql", 1, ExecuteSqlAction) {
            @Override
            ActionResult execute(Action action, Scope scope) throws ActionPerformException {
                return new RewriteResult(new UpdateSqlAction("nested 1"), new UpdateSqlAction("nested 2"));
            }
        })

        then:
        def result = new ActionExecutor().execute(new MockAction(), scope)

        result instanceof CompoundResult
        def nestedActions = new ArrayList<Map.Entry>(((CompoundResult) result).resultsBySource.entrySet())

        nestedActions.size() == 3
        nestedActions[0].key == new UpdateSqlAction("sql action 1")
        nestedActions[0].value.message == "executed sql: sql action 1"

        nestedActions[1].key == new ExecuteSqlAction("exec sql action")
        nestedActions[1].value instanceof CompoundResult
        ((CompoundResult) nestedActions[1].value).results[0].message == "executed sql: nested 1"
        ((CompoundResult) nestedActions[1].value).results[1].message == "executed sql: nested 2"

        nestedActions[2].key == (new UpdateSqlAction("sql action 2"))
        nestedActions[2].value.message == "executed sql: sql action 2"
    }
}
