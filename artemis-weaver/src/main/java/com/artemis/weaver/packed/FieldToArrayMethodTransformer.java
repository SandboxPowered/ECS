package com.artemis.weaver.packed;

import static com.artemis.weaver.packed.InstructionMutator.on;

import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import com.artemis.meta.ClassMetadata;
import com.artemis.meta.ClassMetadataUtil;
import com.artemis.meta.FieldDescriptor;
import com.artemis.transformer.MethodTransformer;

public class FieldToArrayMethodTransformer extends MethodTransformer implements Opcodes {

	private final ClassMetadata meta;
	private final String fieldDesc;
	private final List<String> dataFieldNames;

	public FieldToArrayMethodTransformer(MethodTransformer mt, ClassMetadata meta, List<String> dataFieldNames) {
		super(mt);
		this.meta = meta;
		this.dataFieldNames = dataFieldNames;
		
		FieldDescriptor f = ClassMetadataUtil.instanceFields(meta).get(0);
		fieldDesc = f.desc;
	}
	
	
	@Override
	public void transform(MethodNode mn) {
		InsnList instructions = mn.instructions;
		
		for (int i = 0; instructions.size() > i; i++) {
			AbstractInsnNode node = instructions.get(i);
			switch(node.getType()) {
				case AbstractInsnNode.FIELD_INSN:
					FieldInsnNode f = (FieldInsnNode)node;
					if (isSettingField(f)) {
						i = on(instructions, f)
							.insertAtOffset(2, 
								new FieldInsnNode(GETSTATIC, meta.type.getInternalName(), "$data", "[" + fieldDesc))
							.insertAtOffset(1, 
								new FieldInsnNode(GETFIELD, meta.type.getInternalName(), "$offset", "I"),
								new InsnNode(ICONST_0 + dataFieldNames.indexOf(f.name)),
								new InsnNode(IADD))
							.insertAtOffset(0,
								new InsnNode(FASTORE))
							.delete(0)
							.transform();
					} else if (isGettingField(f)) {
						i = generateGetField(instructions, f);
					}
					break;
				default:
					break;
			}
		}
		
		super.transform(mn);
	}


	private int generateGetField(InsnList source, FieldInsnNode f) {
		int i = source.indexOf(f);
		boolean doDup2 = DUP == source.get(i -1).getOpcode();
		if (doDup2) {
			source.remove(source.get(i - 1));
			i--;
		}
		
		source.insertBefore(source.get(i - 2),
			new FieldInsnNode(GETSTATIC, meta.type.getInternalName(), "$data", "[" + fieldDesc));
		
		i++;
		
		source.insertBefore(f, new FieldInsnNode(GETFIELD, meta.type.getInternalName(), "$offset", "I"));
		source.insertBefore(f, new InsnNode(ICONST_0 + dataFieldNames.indexOf(f.name))); //FIXME only works with <= 5 data fields
		source.insertBefore(f, new InsnNode(IADD));
		if (doDup2) { // FIXME: no-nn, we're just storing the field value on the stack
			source.insertBefore(f, new InsnNode(DUP2));
			i++;
		}
		source.set(f, new InsnNode(FALOAD));
		i += 3;
		
		return i;
	}

	private boolean isSettingField(FieldInsnNode f) {
		return PUTFIELD == f.getOpcode() &&
			f.owner.equals(meta.type.getInternalName()) &&
			f.desc.equals(fieldDesc) &&
			hasInstanceField(meta, f.name);
	}
	
	private boolean isGettingField(FieldInsnNode f) {
		return GETFIELD == f.getOpcode() &&
			f.owner.equals(meta.type.getInternalName()) &&
			f.desc.equals(fieldDesc) &&
			hasInstanceField(meta, f.name);
	}
	
	private static boolean hasInstanceField(ClassMetadata meta, String fieldName) {
		for (FieldDescriptor f : ClassMetadataUtil.instanceFields(meta)) {
			if (f.name.equals(fieldName))
				return true;
		}
		
		return false;
	}
}
