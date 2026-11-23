package scholarchain.spark

import org.apache.spark.sql.{Column, DataFrame, Dataset, SparkSession}
import org.apache.spark.sql.functions.*
import org.apache.spark.sql.types.{StringType, StructField, StructType}

/** A parsed input: the usable rows and the rejected ones (`input`, `reason`), both lazy. */
final case class Parsed(valid: DataFrame, rejected: DataFrame)

/** JSON-lines readers with an explicit schema. A line is rejected (and counted by the caller) when
  * Spark cannot fit it into the schema or when a required column is null; it is never dropped
  * silently and never coerced by schema inference.
  */
object Inputs:
  /** Reads JSON-lines files (`part-*` files resolved by the caller). */
  def readFiles(
      spark: SparkSession,
      files: Seq[String],
      schema: StructType,
      required: Seq[String]
  ): Parsed =
    split(reader(spark, schema).json(files*), required)

  /** Parses in-memory JSON lines (used for fixtures and small side inputs). */
  def parse(lines: Dataset[String], schema: StructType, required: Seq[String]): Parsed =
    split(reader(lines.sparkSession, schema).json(lines), required)

  private def reader(spark: SparkSession, schema: StructType) =
    spark.read
      .schema(schema.add(StructField(Schemas.CorruptColumn, StringType)))
      .option("mode", "PERMISSIVE")
      .option("columnNameOfCorruptRecord", Schemas.CorruptColumn)

  private def split(df: DataFrame, required: Seq[String]): Parsed =
    val corrupt = col(Schemas.CorruptColumn)
    val missing: Column = required.map(c => col(c).isNull).reduce(_ || _)
    val bad = corrupt.isNotNull || missing
    val rejected = df
      .filter(bad)
      .select(
        coalesce(corrupt, to_json(struct(required.map(col)*))).as("input"),
        when(corrupt.isNotNull, lit("SCHEMA_MISMATCH"))
          .otherwise(lit("REQUIRED_FIELD_NULL"))
          .as("reason")
      )
    Parsed(df.filter(!bad).drop(Schemas.CorruptColumn), rejected)
