package net.minecraft.client.data.models.blockstates;

import com.google.common.collect.ImmutableList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.util.Util;
import net.minecraft.world.level.block.state.properties.Property.Value;

public record PropertyValueList(List<Value<?>> values) {
	public static final PropertyValueList EMPTY = new PropertyValueList(List.of());
	private static final Comparator<Value<?>> COMPARE_BY_NAME = Comparator.comparing(p -> p.property().getName());

	public PropertyValueList extend(final Value<?> element) {
		return new PropertyValueList(Util.copyAndAdd(this.values, element));
	}

	public PropertyValueList extend(final PropertyValueList other) {
		return new PropertyValueList(ImmutableList.<Value<?>>builder().addAll(this.values).addAll(other.values).build());
	}

	public static PropertyValueList of(final Value<?>... values) {
		return new PropertyValueList(List.of(values));
	}

	public String getKey() {
		return this.values.stream().sorted(COMPARE_BY_NAME).<CharSequence>map(Value::toString).collect(Collectors.joining(","));
	}

	@Override
	public String toString() {
		return (R)this.getKey();
	}
}
