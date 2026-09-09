package com.kartaguez.pocoma.infra.read.persistence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.jdbc.core.JdbcOperations;
import com.kartaguez.pocoma.domain.pipeline.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.engine.read.projection.PotProjectionArtifactReader;
import com.kartaguez.pocoma.engine.read.projection.ProjectionArtifactWriter;

public final class JdbcPotProjectionArtifactWriter implements ProjectionArtifactWriter<PotProjection>, PotProjectionArtifactReader {
	private static final int DIGEST_FORMAT_VERSION = 1;
	private final JdbcOperations jdbc; private final String schema;
	public JdbcPotProjectionArtifactWriter(JdbcOperations jdbc,String schema){this.jdbc=Objects.requireNonNull(jdbc);if(schema==null||!schema.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IllegalArgumentException("invalid schema");this.schema=schema;}
	@Override public ProjectionContentDigest digest(PotProjection p){
		try { var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes); out.writeInt(DIGEST_FORMAT_VERSION);
			write(out,p.status().name());write(out,p.label());write(out,p.creatorId().value().toString());
			out.writeInt(p.shareholders().size()); for(var s:p.shareholders()){write(out,s.shareholderId().value().toString());write(out,s.name());fraction(out,s.weight());out.writeBoolean(s.userId().isPresent());if(s.userId().isPresent())write(out,s.userId().get().value().toString());out.writeBoolean(s.deleted());}
			out.writeInt(p.expenses().size()); for(var e:p.expenses()){write(out,e.expenseId().value().toString());write(out,e.payerId().value().toString());fraction(out,e.amount());write(out,e.label());out.writeBoolean(e.deleted());out.writeInt(e.shares().size());for(var s:e.shares()){write(out,s.shareholderId().value().toString());fraction(out,s.weight());}}
			return new ProjectionContentDigest(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())));
		} catch(Exception e){throw new IllegalStateException("Cannot digest PotProjection",e);}
	}
	@Override public void write(ProjectionArtifactId artifactId,ProjectionIdentity identity,PotProjection p){
		if(!identity.equals(p.identity()))throw new IllegalArgumentException("artifact identity mismatch"); var g=identity.generation();
		jdbc.update("insert into "+t("pot_projection_snapshots")+" (artifact_id,projection_type,pipeline_id,pipeline_version,pot_id,pot_version,status,label,creator_id) values (?,?,?,?,?,?,?,?,?)",artifactId.value(),g.projectionType().value(),g.pipeline().pipelineId().value(),g.pipeline().pipelineVersion(),g.potId().value(),identity.potVersion(),p.status().name(),p.label(),p.creatorId().value());
		int i=0;for(var s:p.shareholders()){jdbc.update("insert into "+t("pot_projection_shareholders")+" (artifact_id,shareholder_id,ordinal,name,weight_numerator,weight_denominator,user_id,deleted) values (?,?,?,?,?,?,?,?)",artifactId.value(),s.shareholderId().value(),i++,s.name(),s.weight().numerator(),s.weight().denominator(),s.userId().map(u->u.value()).orElse(null),s.deleted());}
		i=0;for(var e:p.expenses()){jdbc.update("insert into "+t("pot_projection_expenses")+" (artifact_id,expense_id,ordinal,payer_id,amount_numerator,amount_denominator,label,deleted) values (?,?,?,?,?,?,?,?)",artifactId.value(),e.expenseId().value(),i++,e.payerId().value(),e.amount().numerator(),e.amount().denominator(),e.label(),e.deleted());int j=0;for(var s:e.shares())jdbc.update("insert into "+t("pot_projection_expense_shares")+" (artifact_id,expense_id,shareholder_id,ordinal,weight_numerator,weight_denominator) values (?,?,?,?,?,?)",artifactId.value(),e.expenseId().value(),s.shareholderId().value(),j++,s.weight().numerator(),s.weight().denominator());}
	}
	@Override public boolean hasSameContent(ProjectionArtifactDescriptor existing,PotProjection proposed){return existing.digest().equals(digest(proposed));}
	@Override public Optional<PotProjection> findByArtifactId(ProjectionArtifactId artifactId) {
		Objects.requireNonNull(artifactId);
		var snapshots = jdbc.query("select projection_type,pipeline_id,pipeline_version,pot_id,pot_version,status,label,creator_id from "
				+t("pot_projection_snapshots")+" where artifact_id=?", (rs,row) -> new SnapshotRow(
				new ProjectionIdentity(new ProjectionGenerationIdentity(new ProjectionType(rs.getString(1)),
						new PipelineDefinition(PipelineId.of(rs.getString(2)),rs.getInt(3)),PotId.of(rs.getObject(4,UUID.class))),rs.getLong(5)),
				PotProjectionStatus.valueOf(rs.getString(6)),rs.getString(7),UserId.of(rs.getObject(8,UUID.class))),artifactId.value());
		if(snapshots.isEmpty()) return Optional.empty();
		if(snapshots.size()!=1) throw new IllegalStateException("duplicate Pot snapshot artifact");
		var shareholders=jdbc.query("select shareholder_id,name,weight_numerator,weight_denominator,user_id,deleted from "+t("pot_projection_shareholders")+" where artifact_id=? order by ordinal",(rs,row)->new PotProjectionShareholder(ShareholderId.of(rs.getObject(1,UUID.class)),rs.getString(2),Fraction.of(rs.getLong(3),rs.getLong(4)),Optional.ofNullable(rs.getObject(5,UUID.class)).map(UserId::of),rs.getBoolean(6)),artifactId.value());
		var expenses=jdbc.query("select expense_id,payer_id,amount_numerator,amount_denominator,label,deleted from "+t("pot_projection_expenses")+" where artifact_id=? order by ordinal",(rs,row)->new ExpenseRow(ExpenseId.of(rs.getObject(1,UUID.class)),ShareholderId.of(rs.getObject(2,UUID.class)),Fraction.of(rs.getLong(3),rs.getLong(4)),rs.getString(5),rs.getBoolean(6)),artifactId.value()).stream().map(e->new PotProjectionExpense(e.expenseId(),e.payerId(),e.amount(),e.label(),e.deleted(),jdbc.query("select shareholder_id,weight_numerator,weight_denominator from "+t("pot_projection_expense_shares")+" where artifact_id=? and expense_id=? order by ordinal",(rs,row)->new PotProjectionExpenseShare(ShareholderId.of(rs.getObject(1,UUID.class)),Fraction.of(rs.getLong(2),rs.getLong(3))),artifactId.value(),e.expenseId().value()))).toList();
		var snapshot=snapshots.getFirst();
		return Optional.of(new PotProjection(snapshot.identity(),snapshot.status(),snapshot.label(),snapshot.creatorId(),shareholders,expenses));
	}
	private record SnapshotRow(ProjectionIdentity identity,PotProjectionStatus status,String label,UserId creatorId){}
	private record ExpenseRow(ExpenseId expenseId,ShareholderId payerId,Fraction amount,String label,boolean deleted){}
	private String t(String n){return schema+"."+n;} private static void write(DataOutputStream o,String s)throws IOException{byte[] b=s.getBytes(StandardCharsets.UTF_8);o.writeInt(b.length);o.write(b);} private static void fraction(DataOutputStream o,com.kartaguez.pocoma.domain.pot.value.Fraction f)throws IOException{o.writeLong(f.numerator());o.writeLong(f.denominator());}
}
