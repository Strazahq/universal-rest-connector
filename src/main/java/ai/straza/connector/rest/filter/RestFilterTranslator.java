package ai.straza.connector.rest.filter;

import java.util.List;

import org.identityconnectors.common.logging.Log;
import org.identityconnectors.framework.common.objects.Attribute;
import org.identityconnectors.framework.common.objects.filter.AbstractFilterTranslator;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;

/**
 * Translates equals and contains-all-values filters into a {@link RestFilter}.
 * Other filters, negations included, are left to the framework, which filters
 * the full listing.
 */
public class RestFilterTranslator extends AbstractFilterTranslator<RestFilter> {

    private static final Log LOG = Log.getLog(RestFilterTranslator.class);

    @Override
    protected RestFilter createEqualsExpression(EqualsFilter filter, boolean not) {
        LOG.ok("createEqualsExpression, filter: {0}, not: {1}", filter, not);
        if (not) {
            return null;
        }
        return translateAttribute(filter.getAttribute());
    }

    @Override
    protected RestFilter createContainsAllValuesExpression(ContainsAllValuesFilter filter, boolean not) {
        LOG.ok("createContainsAllValuesExpression, filter: {0}, not: {1}", filter, not);
        if (not) {
            return null;
        }
        return translateAttribute(filter.getAttribute());
    }

    private static RestFilter translateAttribute(Attribute attribute) {
        List<Object> values = attribute.getValue();
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return null;
        }
        return new RestFilter(attribute.getName(), values);
    }
}
