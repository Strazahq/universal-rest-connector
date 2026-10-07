package ai.straza.connector.rest.filter;

import java.util.List;

import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.Name;
import org.identityconnectors.framework.common.objects.Uid;
import org.identityconnectors.framework.common.objects.filter.ContainsAllValuesFilter;
import org.identityconnectors.framework.common.objects.filter.EqualsFilter;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Equality and contains-all-values filters both become one attribute query. */
@Test(groups = "unit")
public class RestFilterTranslatorTest {

    public void translatesUidEquality() {
        List<RestFilter> result = new RestFilterTranslator()
                .translate(new EqualsFilter(AttributeBuilder.build(Uid.NAME, "u1")));
        assertEquals(result.size(), 1);
        assertEquals(result.get(0).getAttribute(), Uid.NAME);
        assertEquals(result.get(0).getValues(), List.of("u1"));
        assertTrue(result.get(0).isUidQuery());
    }

    public void translatesNameEquality() {
        List<RestFilter> result = new RestFilterTranslator()
                .translate(new EqualsFilter(AttributeBuilder.build(Name.NAME, "alice")));
        assertEquals(result.size(), 1);
        assertEquals(result.get(0).getAttribute(), Name.NAME);
        assertEquals(result.get(0).singleValue(), "alice");
    }

    public void anyOtherAttributeTranslatesTheSameWay() {
        // No query would mean an unfiltered search, which midPoint reads as matching everything.
        List<RestFilter> result = new RestFilterTranslator()
                .translate(new EqualsFilter(AttributeBuilder.build("email", "a@b.c")));
        assertEquals(result.size(), 1);
        assertEquals(result.get(0).getAttribute(), "email");
        assertEquals(result.get(0).getValues(), List.of("a@b.c"));
    }

    public void containsAllValuesTranslatesToTheSameQuery() {
        // midPoint uses contains-all-values for multivalued attributes, e.g. resolving associations.
        List<RestFilter> result = new RestFilterTranslator().translate(
                new ContainsAllValuesFilter(AttributeBuilder.build("members", List.of("u-1", "u-2"))));
        assertEquals(result.size(), 1);
        assertEquals(result.get(0).getAttribute(), "members");
        assertEquals(result.get(0).getValues(), List.of("u-1", "u-2"));
        assertTrue(!result.get(0).isSingleValue(), "a multi-value query may never ride a server-side lane");
    }

    public void substringFiltersStillYieldNoQuery() {
        // Other filter shapes are left to the framework's own post-filtering.
        List<RestFilter> result = new RestFilterTranslator()
                .translate(new org.identityconnectors.framework.common.objects.filter.ContainsFilter(
                        AttributeBuilder.build("email", "a@")));
        assertTrue(result.isEmpty());
    }
}
